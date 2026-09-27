package com.lifeos.app.places.data

import com.lifeos.app.trips.location.StopAction
import com.lifeos.app.trips.location.StopDetection
import com.lifeos.app.trips.location.StopNamer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Everyday places: every kept location, the stops made from them, and saved places like Home. */
@Singleton
class PlacesRepository @Inject constructor(
    private val dao: PlacesDao,
    private val stopNamer: StopNamer,
) {
    // Locations arrive in batches; each must see the stop state the previous one left.
    private val recordLock = Mutex()
    /** Where the user said "Not a stop" while still there (see TripRepository). */
    @Volatile private var dismissedAt: Pair<Double, Double>? = null
    @Volatile private var lastPruneAt = 0L

    fun observeStopsBetween(start: Long, end: Long): Flow<List<PlaceStopEntity>> = dao.observeStopsBetween(start, end)
    fun observeSamplesBetween(start: Long, end: Long): Flow<List<PlaceSampleEntity>> = dao.observeSamplesBetween(start, end)
    fun observeSavedPlaces(): Flow<List<SavedPlaceEntity>> = dao.observeSavedPlaces()
    suspend fun stopsSince(since: Long): List<PlaceStopEntity> = dao.stopsSince(since)

    suspend fun record(sample: PlaceSampleEntity) = recordLock.withLock {
        if (sample.accuracyMeters > StopDetection.MAX_ACCURACY_METERS) return@withLock
        dismissedAt?.let { (lat, lng) ->
            if (StopDetection.distanceMeters(lat, lng, sample.latitude, sample.longitude) > StopDetection.LEAVE_METERS) {
                dismissedAt = null
            }
        }
        val previous = dao.lastSample()
        dao.insertSample(sample)
        val action = StopDetection.nextStopAction(
            open = dao.openStop(),
            previous = previous,
            sample = sample,
            suppressNew = dismissedAt != null,
            recentStops = { dao.stopsSince(sample.recordedAt - RECENT_STOPS_MILLIS) },
            window = { dao.samplesSince(sample.recordedAt - StopDetection.MIN_STAY_MILLIS) },
        )
        when (action) {
            StopAction.None -> Unit
            is StopAction.Leave -> dao.upsertStop(action.stop.copy(departedAt = action.at))
            is StopAction.Extend -> dao.upsertStop(action.stop.copy(departedAt = action.until))
            is StopAction.Start -> createStop(action.latitude, action.longitude, action.arrivedAt, action.departedAt)
        }
        namePendingStops()
        pruneOldSamples(sample.recordedAt)
    }

    private suspend fun createStop(latitude: Double, longitude: Double, arrivedAt: Long, departedAt: Long?) {
        val saved = savedPlaceNear(latitude, longitude, dao.savedPlaces())
        val address = if (saved == null) stopNamer.name(latitude, longitude) else null
        dao.upsertStop(
            PlaceStopEntity(
                id = UUID.randomUUID().toString(),
                latitude = latitude,
                longitude = longitude,
                name = saved?.name ?: address ?: UNNAMED_STOP,
                nameSource = when {
                    saved != null -> NameSource.SAVED
                    address != null -> NameSource.ADDRESS
                    else -> NameSource.PENDING
                },
                arrivedAt = arrivedAt,
                departedAt = departedAt,
                savedPlaceId = saved?.id,
            )
        )
    }

    /** Stops made offline keep the placeholder until an address lookup succeeds. */
    private suspend fun namePendingStops() {
        dao.pendingNameStops().forEach { stop ->
            val name = stopNamer.name(stop.latitude, stop.longitude) ?: return
            dao.upsertStop(stop.copy(name = name, nameSource = NameSource.ADDRESS))
        }
    }

    private suspend fun pruneOldSamples(now: Long) {
        if (now - lastPruneAt < PRUNE_EVERY_MILLIS) return
        lastPruneAt = now
        dao.deleteSamplesBefore(now - SAMPLE_RETENTION_DAYS * DAY_MILLIS)
    }

    suspend fun renameStop(id: String, name: String) {
        val stop = dao.getStop(id) ?: return
        val trimmed = name.trim().ifEmpty { return }
        dao.upsertStop(stop.copy(name = trimmed, nameSource = NameSource.USER, savedPlaceId = null))
    }

    /** Removes an inferred stop; the recorded locations are kept. */
    suspend fun removeStop(id: String) {
        val stop = dao.getStop(id) ?: return
        if (stop.departedAt == null) dismissedAt = stop.latitude to stop.longitude
        dao.deleteStop(id)
    }

    /**
     * Saves Home or Work here (replacing the previous one) and renames the stops
     * around it that only had an address or placeholder name.
     */
    suspend fun savePlace(kind: String, latitude: Double, longitude: Double) {
        val name = when (kind) {
            SavedPlaceKind.HOME -> "Home"
            SavedPlaceKind.WORK -> "Work"
            else -> return
        }
        dao.savedPlaces().filter { it.kind == kind }.forEach { old ->
            dao.stopsSince(0).filter { it.savedPlaceId == old.id }.forEach {
                dao.upsertStop(it.copy(savedPlaceId = null, nameSource = NameSource.PENDING, name = UNNAMED_STOP))
            }
        }
        dao.deleteSavedPlaces(kind)
        val place = SavedPlaceEntity(UUID.randomUUID().toString(), kind, name, latitude, longitude)
        dao.upsertSavedPlace(place)
        dao.stopsSince(0)
            .filter { it.nameSource != NameSource.USER }
            .filter { StopDetection.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= SAVED_MATCH_METERS }
            .forEach { dao.upsertStop(it.copy(name = name, nameSource = NameSource.SAVED, savedPlaceId = place.id)) }
        namePendingStops()
    }

    /** "This isn't home": the suggestion there is not offered again. */
    suspend fun dismissHomeSuggestion(latitude: Double, longitude: Double) {
        dao.upsertSavedPlace(
            SavedPlaceEntity(UUID.randomUUID().toString(), SavedPlaceKind.NOT_HOME, "Not home", latitude, longitude)
        )
    }

    suspend fun clearSavedPlace(kind: String) {
        val ids = dao.savedPlaces().filter { it.kind == kind }.map { it.id }.toSet()
        dao.deleteSavedPlaces(kind)
        dao.stopsSince(0).filter { it.savedPlaceId in ids }.forEach {
            dao.upsertStop(it.copy(savedPlaceId = null, nameSource = NameSource.PENDING, name = UNNAMED_STOP))
        }
        namePendingStops()
    }

    /** Deletes every location, stop and saved place on this phone. */
    suspend fun deleteHistory() = recordLock.withLock {
        dao.deleteAllSamples()
        dao.deleteAllStops()
        dao.deleteAllSavedPlaces()
        dismissedAt = null
    }

    companion object {
        const val UNNAMED_STOP = "Unnamed stop"
        const val SAMPLE_RETENTION_DAYS = 30L
        /** A stop this close to Home or Work is that place. */
        const val SAVED_MATCH_METERS = 150.0
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val RECENT_STOPS_MILLIS = DAY_MILLIS
        private const val PRUNE_EVERY_MILLIS = 60 * 60 * 1000L

        fun savedPlaceNear(latitude: Double, longitude: Double, saved: List<SavedPlaceEntity>): SavedPlaceEntity? =
            saved.filter { it.kind == SavedPlaceKind.HOME || it.kind == SavedPlaceKind.WORK }
                .map { it to StopDetection.distanceMeters(latitude, longitude, it.latitude, it.longitude) }
                .filter { (_, meters) -> meters <= SAVED_MATCH_METERS }
                .minByOrNull { (_, meters) -> meters }
                ?.first
    }
}
