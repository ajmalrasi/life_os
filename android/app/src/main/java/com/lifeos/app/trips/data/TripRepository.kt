package com.lifeos.app.trips.data

import com.lifeos.app.data.local.TransactionDao
import com.lifeos.app.data.local.TransactionEntity
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.trips.importer.TripImportPreview
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TripRepository @Inject constructor(
    private val dao: TripDao,
    private val api: LifeOsApi,
    private val fileStore: TripFileStore,
    private val transactionDao: TransactionDao,
    private val workScheduler: WorkScheduler,
    private val settingsRepository: SettingsRepository,
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
        dao.insertLocation(sample)
        updateVisit(sample)
    }

    private suspend fun updateVisit(sample: TripLocationSampleEntity) {
        val open = dao.openVisit(sample.tripId)
        if (open != null) {
            if (distanceMeters(open.latitude, open.longitude, sample.latitude, sample.longitude) > 200) {
                dao.upsertVisit(open.copy(departedAt = sample.recordedAt))
            }
            return
        }
        val window = dao.recentLocations(sample.tripId, sample.recordedAt - EIGHT_MINUTES)
            .filter { it.accuracyMeters <= 100f }
        if (window.size < 3 || window.last().recordedAt - window.first().recordedAt < EIGHT_MINUTES) return
        val centerLat = window.map { it.latitude }.average()
        val centerLng = window.map { it.longitude }.average()
        if (window.any { distanceMeters(centerLat, centerLng, it.latitude, it.longitude) > 150 }) return
        dao.upsertVisit(
            TripVisitEntity(
                id = UUID.randomUUID().toString(),
                tripId = sample.tripId,
                name = "Unconfirmed stop",
                latitude = centerLat,
                longitude = centerLng,
                arrivedAt = window.first().recordedAt,
            )
        )
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return earth * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object { const val EIGHT_MINUTES = 8 * 60 * 1000L }
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
