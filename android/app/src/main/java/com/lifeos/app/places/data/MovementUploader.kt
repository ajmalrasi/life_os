package com.lifeos.app.places.data

import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.places.domain.DaySummary
import com.lifeos.app.places.domain.PlacesTimeline
import com.lifeos.app.trips.data.TripRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Sends the last week's Places summaries and stops to the LifeOS server, so
 * Insights, the weekly recap and Ask can see them. The raw route never leaves
 * the phone. Re-sending a day replaces it, so this is safe to run often.
 */
@Singleton
class MovementUploader @Inject constructor(
    private val dao: PlacesDao,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val api: LifeOsApi,
) {
    /** Returns the number of days sent; 0 when Places has nothing to send. */
    suspend fun uploadRecent(days: Int = UPLOAD_DAYS): Int {
        val zone = ZoneId.systemDefault()
        val saved = dao.savedPlaces()
        val home = saved.firstOrNull { it.kind == SavedPlaceKind.HOME }
        val today = LocalDate.now(zone)
        val payload = (0 until days).mapNotNull { back ->
            val date = today.minusDays(back.toLong())
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val samples = dao.samplesBetween(start, end)
            // Stops that began this day; the server replaces the day's stops with these.
            val stops = dao.stopsBetween(start, end).filter { it.arrivedAt >= start }
            if (samples.isEmpty() && stops.isEmpty()) return@mapNotNull null
            val tripStays = tripRepository.observeStaysBetween(start, end).first()
            val movement = DaySummary.of(samples, home?.let { it.latitude to it.longitude }, zone)
            MovementDayDto(
                day = date.toString(),
                distanceKm = movement.distanceMeters / 1_000,
                vehicleKm = movement.vehicleMeters / 1_000,
                awayMinutes = movement.awayMillis?.let { (it / 60_000).toInt() },
                lateAwayMinutes = movement.lateAwayMillis?.let { (it / 60_000).toInt() },
                farthestKm = movement.farthestFromHomeMeters?.let { (it / 10).roundToInt() / 100.0 },
                tripTitle = tripStays.firstOrNull()?.tripTitle,
                stops = stops.map { stop ->
                    val stay = PlacesTimeline.stay(stop, tripStays)
                    PlaceStopDto(
                        id = stop.id,
                        name = stay.name,
                        kind = saved.firstOrNull { it.id == stop.savedPlaceId }?.kind,
                        latitude = stop.latitude,
                        longitude = stop.longitude,
                        arrivedAt = Instant.ofEpochMilli(stop.arrivedAt).toString(),
                        departedAt = stop.departedAt?.let { Instant.ofEpochMilli(it).toString() },
                        tripTitle = stay.tripTitle,
                    )
                },
            )
        }
        if (payload.isEmpty()) return 0
        api.uploadMovement(MovementUploadDto(payload))
        return payload.size
    }

    /** Only when Places is on, so turning it off stops uploads too. */
    suspend fun uploadIfEnabled(): Int = if (settingsRepository.placesEnabled.first()) uploadRecent() else 0

    suspend fun deleteFromServer() {
        api.deleteMovement()
    }

    companion object {
        const val UPLOAD_DAYS = 7
    }
}
