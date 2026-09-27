package com.lifeos.app.trips.data

import com.lifeos.app.data.local.TransactionDao
import com.lifeos.app.data.local.TransactionEntity
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.trips.importer.TripImportPreview
import com.lifeos.app.trips.location.StopDetection
import com.lifeos.app.trips.location.StopNamer
import com.lifeos.app.work.WorkScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TripRepository @Inject constructor(
    private val dao: TripDao,
    private val api: LifeOsApi,
    private val fileStore: TripFileStore,
    private val transactionDao: TransactionDao,
    private val workScheduler: WorkScheduler,
    private val settingsRepository: SettingsRepository,
    private val stopNamer: StopNamer,
) {
    /** Best-effort: enqueue a debounced backup after a user edit, only when the
     * "Back up trips automatically" setting is on. Never blocks the caller. */
    private suspend fun scheduleAutoBackup(tripId: String) {
        if (settingsRepository.tripAutoBackupEnabled.first()) {
            workScheduler.enqueueTripBackup(tripId)
        }
    }

    fun observeTrips(): Flow<List<TripEntity>> = dao.observeTrips()
    fun observeTrip(id: String): Flow<TripDetail?> = dao.observeTrip(id)

    /** Cached debits inside the trip's date range that aren't already linked to
     * a trip expense — offered to the user as one-tap "add as trip expense"
     * suggestions instead of manual re-entry. Money's cache, not a network call,
     * so this works offline like the rest of Trips. */
    fun observeSuggestedExpenses(tripId: String): Flow<List<TransactionEntity>> =
        dao.observeTrip(tripId).flatMapLatest { detail ->
            if (detail == null) return@flatMapLatest flowOf(emptyList())
            val linked = detail.expenses.mapNotNull { it.transactionId }.toSet()
            transactionDao.observeBetween(detail.trip.startDate, detail.trip.endDate)
                .map { txns -> txns.filter { it.id !in linked } }
        }
    fun observeLocations(id: String): Flow<List<TripLocationSampleEntity>> = dao.observeLocations(id)
    suspend fun activeTrip(): TripEntity? = dao.activeTrip()
    suspend fun activeTrips(): List<TripEntity> = dao.activeTrips()

    /** Only one trip can record at a time: the service follows a single trip,
     * so any other trip still marked active is paused. */
    suspend fun markTracking(id: String) {
        dao.activeTrips().filter { it.id != id }.forEach { setStatus(it.id, "paused") }
        setStatus(id, "active")
        pruneOutsideTrip(id)
    }

    private fun belongsToTrip(trip: TripEntity, latitude: Double, longitude: Double, at: Long, places: List<TripPlaceEntity>) =
        StopDetection.withinTripDates(at, trip.startDate, trip.endDate, trip.timezone) &&
            StopDetection.withinTripArea(latitude, longitude, places)

    /** Removes locations and stops recorded outside the trip's days or area
     * (before these rules existed, or after the trip's dates were edited). */
    private suspend fun pruneOutsideTrip(tripId: String) {
        val trip = dao.tripById(tripId) ?: return
        val places = dao.placesForTrip(tripId)
        val locations = dao.getLocations(tripId)
            .filterNot { belongsToTrip(trip, it.latitude, it.longitude, it.recordedAt, places) }
        val visits = dao.visitsForTrip(tripId)
            .filterNot { belongsToTrip(trip, it.latitude, it.longitude, it.arrivedAt, places) }
        if (locations.isEmpty() && visits.isEmpty()) return
        locations.map { it.id }.chunked(500).forEach { dao.deleteLocations(it) }
        visits.forEach { dao.deleteVisit(it.id) }
        scheduleAutoBackup(tripId)
    }

    /** What the tracking notification should say for [tripId] right now. */
    suspend fun trackingState(tripId: String, now: Long = System.currentTimeMillis()): String? {
        val trip = dao.tripById(tripId) ?: return null
        if (StopDetection.withinTripDates(now, trip.startDate, trip.endDate, trip.timezone)) return null
        val zone = runCatching { java.time.ZoneId.of(trip.timezone) }.getOrDefault(java.time.ZoneId.systemDefault())
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = java.time.LocalDate.parse(trip.startDate)
        return if (today.isBefore(start)) {
            "Waiting for the trip. Recording starts " +
                start.format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM")) + "."
        } else {
            "The trip has ended, so nothing is being recorded. Pause tracking to stop."
        }
    }
    suspend fun import(bundle: ImportedTripBundle, replaceExisting: Boolean = false) {
        if (replaceExisting) {
            dao.replaceTripItinerary(bundle)
        } else {
            dao.importTrip(bundle)
        }
        scheduleAutoBackup(bundle.trip.id)
    }
    suspend fun setStatus(id: String, status: String) {
        dao.setStatus(id, status)
        scheduleAutoBackup(id)
    }
    suspend fun setActivityStatus(id: String, status: String) {
        dao.setActivityStatus(id, status)
        dao.activitiesForDayForActivity(id)?.let { scheduleAutoBackup(it.tripId) }
    }
    suspend fun setReminderEnabled(id: String, enabled: Boolean) = dao.setReminderEnabled(id, enabled)
    suspend fun resolvePlace(id: String, latitude: Double, longitude: Double) = dao.resolvePlace(id, latitude, longitude)
    suspend fun savePlace(place: TripPlaceEntity) = dao.upsertPlace(place)
    suspend fun removePlace(id: String) = dao.removePlace(id)
    suspend fun saveScheduledStop(
        place: TripPlaceEntity,
        activity: TripActivityEntity,
        previousDayId: String?,
        position: Int,
    ) = dao.saveScheduledStop(place, activity, previousDayId, position)
    suspend fun removeScheduledStop(activityId: String, placeId: String?) =
        dao.removeScheduledStop(activityId, placeId)
    suspend fun delete(id: String) {
        dao.deleteTrip(id)
        fileStore.deleteTripFiles(id)
        runCatching { api.deleteTripBackup(id) }
    }
    suspend fun addExpense(expense: TripExpenseEntity) {
        dao.upsertExpense(expense)
        scheduleAutoBackup(expense.tripId)
    }
    suspend fun addAttachment(attachment: TripAttachmentEntity) = dao.upsertAttachment(attachment)

    suspend fun backup(tripId: String): Int {
        val detail = requireNotNull(dao.getTrip(tripId)) { "Trip no longer exists." }
        val locations = dao.getLocations(tripId)
        val response = api.backupTrip(
            tripId,
            TripBackupRequestDto(
                title = detail.trip.title,
                baseRevision = detail.trip.backupRevision,
                mutationId = UUID.randomUUID().toString(),
                payload = detail.toBackupJson(locations),
            ),
        )
        // Persist the server revision before file uploads. If a file transfer fails,
        // the next retry must build on this accepted snapshot rather than conflict.
        dao.markBackedUp(tripId, response.revision)
        detail.attachments.forEach { attachment ->
            val upload = api.backupTripAttachment(
                tripId, attachment.id, attachment.displayName,
                fileStore.requestBody(attachment.localUri),
            )
            check(upload.isSuccessful) { "Could not back up ${attachment.displayName}." }
        }
        return response.revision
    }

    suspend fun record(sample: TripLocationSampleEntity) {
        if (sample.accuracyMeters > 100f) return
        // Only the trip's days and area are part of the trip: not home, not the journey there.
        val trip = dao.tripById(sample.tripId) ?: return
        if (!belongsToTrip(trip, sample.latitude, sample.longitude, sample.recordedAt, dao.placesForTrip(trip.id))) {
            dao.openVisit(sample.tripId)?.let { dao.upsertVisit(it.copy(departedAt = sample.recordedAt)) }
            return
        }
        val previous = dao.lastLocation(sample.tripId)
        dao.insertLocation(sample)
        updateVisit(sample, previous)
        nameUnnamedStops(sample.tripId)
    }

    /** Stops recorded offline keep the placeholder until an address lookup succeeds. */
    private suspend fun nameUnnamedStops(tripId: String) {
        dao.visitsForTrip(tripId)
            .filter { it.placeId == null && !it.confirmed && it.name == UNCONFIRMED_STOP }
            .forEach { visit ->
                val name = stopNamer.name(visit.latitude, visit.longitude) ?: return
                dao.upsertVisit(visit.copy(name = name))
                scheduleAutoBackup(tripId)
            }
    }

    /** Where the user said "Not a stop" while still there; no new stop is made
     * there until they move away, or it would reappear minutes later. */
    private data class DismissedStop(val tripId: String, val latitude: Double, val longitude: Double)
    @Volatile private var dismissedStop: DismissedStop? = null

    private suspend fun updateVisit(sample: TripLocationSampleEntity, previous: TripLocationSampleEntity?) {
        dismissedStop?.let {
            if (it.tripId == sample.tripId && distanceMeters(it.latitude, it.longitude, sample.latitude, sample.longitude) > StopDetection.LEAVE_METERS) {
                dismissedStop = null
            }
        }
        val open = dao.openVisit(sample.tripId)
        if (open != null) {
            if (distanceMeters(open.latitude, open.longitude, sample.latitude, sample.longitude) > StopDetection.LEAVE_METERS) {
                dao.upsertVisit(open.copy(departedAt = sample.recordedAt))
            }
            return
        }
        if (dismissedStop?.tripId == sample.tripId) return
        if (previous != null && StopDetection.stayedBetween(previous, sample)) {
            // A long stay arrives as several gaps in a row: extend the stop that
            // ended at the previous sample instead of starting another one.
            val continuing = dao.visitsForTrip(sample.tripId).firstOrNull {
                it.departedAt != null && it.departedAt >= previous.recordedAt &&
                    distanceMeters(it.latitude, it.longitude, previous.latitude, previous.longitude) <= StopDetection.LEAVE_METERS
            }
            if (continuing != null) {
                if (sample.recordedAt > continuing.departedAt!!) dao.upsertVisit(continuing.copy(departedAt = sample.recordedAt))
            } else {
                createVisit(sample.tripId, previous.latitude, previous.longitude, previous.recordedAt, sample.recordedAt)
            }
            return
        }
        val window = dao.recentLocations(sample.tripId, sample.recordedAt - StopDetection.MIN_STAY_MILLIS)
            .filter { it.accuracyMeters <= 100f }
        if (window.size < 3 || window.last().recordedAt - window.first().recordedAt < StopDetection.MIN_STAY_MILLIS) return
        val centerLat = window.map { it.latitude }.average()
        val centerLng = window.map { it.longitude }.average()
        if (window.any { distanceMeters(centerLat, centerLng, it.latitude, it.longitude) > StopDetection.STAY_RADIUS_METERS }) return
        createVisit(sample.tripId, centerLat, centerLng, window.first().recordedAt, departedAt = null)
    }

    /** A stop at a planned place takes that place's name and counts as confirmed. */
    private suspend fun createVisit(tripId: String, latitude: Double, longitude: Double, arrivedAt: Long, departedAt: Long?) {
        val places = dao.placesForTrip(tripId)
        val place = StopDetection.nearbyPlaces(latitude, longitude, places, StopDetection.AUTO_MATCH_METERS)
            .firstOrNull()
        dao.upsertVisit(
            TripVisitEntity(
                id = UUID.randomUUID().toString(),
                tripId = tripId,
                placeId = place?.id,
                name = place?.name ?: stopNamer.name(latitude, longitude) ?: UNCONFIRMED_STOP,
                latitude = latitude,
                longitude = longitude,
                arrivedAt = arrivedAt,
                departedAt = departedAt,
                confirmed = place != null,
            )
        )
        scheduleAutoBackup(tripId)
    }

    suspend fun confirmVisitAs(visitId: String, placeId: String) {
        val visit = dao.getVisit(visitId) ?: return
        val place = dao.placesForTrip(visit.tripId).firstOrNull { it.id == placeId } ?: return
        dao.upsertVisit(visit.copy(placeId = place.id, name = place.name, confirmed = true))
        scheduleAutoBackup(visit.tripId)
    }

    suspend fun renameVisit(visitId: String, name: String) {
        val visit = dao.getVisit(visitId) ?: return
        val trimmed = name.trim().ifEmpty { return }
        dao.upsertVisit(visit.copy(placeId = null, name = trimmed, confirmed = true))
        scheduleAutoBackup(visit.tripId)
    }

    /** Saves an off-plan stop as a place in the trip and links the stop to it. */
    suspend fun addVisitToPlan(visitId: String, name: String) {
        val visit = dao.getVisit(visitId) ?: return
        val trimmed = name.trim().ifEmpty { return }
        val place = TripPlaceEntity(
            id = UUID.randomUUID().toString(),
            tripId = visit.tripId,
            name = trimmed,
            latitude = visit.latitude,
            longitude = visit.longitude,
            verification = "supplied",
        )
        dao.upsertPlace(place)
        dao.upsertVisit(visit.copy(placeId = place.id, name = trimmed, confirmed = true))
        scheduleAutoBackup(visit.tripId)
    }

    /** Removes an inferred stop; the recorded route is kept. */
    suspend fun removeVisit(visitId: String) {
        val visit = dao.getVisit(visitId) ?: return
        if (visit.departedAt == null) dismissedStop = DismissedStop(visit.tripId, visit.latitude, visit.longitude)
        dao.deleteVisit(visitId)
        scheduleAutoBackup(visit.tripId)
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        StopDetection.distanceMeters(lat1, lng1, lat2, lng2)

    companion object { const val UNCONFIRMED_STOP = "Unconfirmed stop" }
}

private fun TripDetail.toBackupJson(locations: List<TripLocationSampleEntity>) = JsonObject(
    mapOf(
        "schema_version" to JsonPrimitive(2),
        "trip" to JsonObject(mapOf(
            "id" to JsonPrimitive(trip.id), "title" to JsonPrimitive(trip.title),
            "start_date" to JsonPrimitive(trip.startDate), "end_date" to JsonPrimitive(trip.endDate),
            "timezone" to JsonPrimitive(trip.timezone), "currency" to JsonPrimitive(trip.currency),
            "status" to JsonPrimitive(trip.status),
        )),
        "days" to JsonArray(days.sortedBy { it.sortOrder }.map { JsonObject(mapOf(
            "id" to JsonPrimitive(it.id), "date" to JsonPrimitive(it.date), "title" to JsonPrimitive(it.title),
        )) }),
        "places" to JsonArray(places.map { JsonObject(mapOf(
            "id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name),
            "address" to (it.address?.let(::JsonPrimitive) ?: JsonNull),
            "latitude" to (it.latitude?.let(::JsonPrimitive) ?: JsonNull),
            "longitude" to (it.longitude?.let(::JsonPrimitive) ?: JsonNull),
        )) }),
        "activities" to JsonArray(activities.map { act ->
            val fields = mutableMapOf<String, JsonElement>(
                "id" to JsonPrimitive(act.id),
                "day_id" to JsonPrimitive(act.dayId),
                "title" to JsonPrimitive(act.title),
                "type" to JsonPrimitive(act.type),
                "status" to JsonPrimitive(act.status),
                "notes" to (act.notes?.let(::JsonPrimitive) ?: JsonNull),
                "place_id" to (act.placeId?.let(::JsonPrimitive) ?: JsonNull),
                "from_place_id" to (act.fromPlaceId?.let(::JsonPrimitive) ?: JsonNull),
                "to_place_id" to (act.toPlaceId?.let(::JsonPrimitive) ?: JsonNull),
                "transport_mode" to (act.transportMode?.let(::JsonPrimitive) ?: JsonNull),
            )
            act.stepsJson?.let { stepsRaw ->
                runCatching { Json.parseToJsonElement(stepsRaw) }.getOrNull()?.let {
                    fields["steps"] = it
                }
            }
            JsonObject(fields)
        }),
        "visits" to JsonArray(visits.map { JsonObject(mapOf(
            "id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name),
            "latitude" to JsonPrimitive(it.latitude), "longitude" to JsonPrimitive(it.longitude),
            "arrived_at" to JsonPrimitive(it.arrivedAt), "departed_at" to (it.departedAt?.let(::JsonPrimitive) ?: JsonNull),
            "place_id" to (it.placeId?.let(::JsonPrimitive) ?: JsonNull), "confirmed" to JsonPrimitive(it.confirmed),
        )) }),
        "locations" to JsonArray(locations.map { JsonObject(mapOf(
            "latitude" to JsonPrimitive(it.latitude), "longitude" to JsonPrimitive(it.longitude),
            "accuracy_meters" to JsonPrimitive(it.accuracyMeters), "recorded_at" to JsonPrimitive(it.recordedAt),
        )) }),
        "expenses" to JsonArray(expenses.map { JsonObject(mapOf(
            "id" to JsonPrimitive(it.id), "title" to JsonPrimitive(it.title), "amount" to JsonPrimitive(it.amount),
            "currency" to JsonPrimitive(it.currency), "category" to JsonPrimitive(it.category), "paid_at" to JsonPrimitive(it.paidAt),
        )) }),
        "attachments" to JsonArray(attachments.map { JsonObject(mapOf(
            "id" to JsonPrimitive(it.id), "kind" to JsonPrimitive(it.kind), "display_name" to JsonPrimitive(it.displayName),
        )) }),
    )
)
