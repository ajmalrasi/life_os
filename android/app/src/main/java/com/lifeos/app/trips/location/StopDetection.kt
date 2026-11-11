package com.lifeos.app.trips.location

import com.lifeos.app.trips.data.TripPlaceEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A recorded position, from a trip or from Places. */
interface LocationFix {
    val latitude: Double
    val longitude: Double
    val accuracyMeters: Float
    val recordedAt: Long
}

/** A stay somewhere: open while [departedAt] is null. */
interface StopSpan {
    val latitude: Double
    val longitude: Double
    val arrivedAt: Long
    val departedAt: Long?
}

/** What a new location means for the stops recorded so far. */
sealed interface StopAction<out S : StopSpan> {
    data object None : StopAction<Nothing>
    /** The user left the open stop [stop] at [at]. */
    data class Leave<S : StopSpan>(val stop: S, val at: Long) : StopAction<S>
    /** A stop that ended at the previous location continues until [until]. */
    data class Extend<S : StopSpan>(val stop: S, val until: Long) : StopAction<S>
    /** A new stop; open when [departedAt] is null. */
    data class Start(val latitude: Double, val longitude: Double, val arrivedAt: Long, val departedAt: Long?) :
        StopAction<Nothing>
}

/** Rules for turning recorded locations into stops and naming them from the plan. */
object StopDetection {
    const val MIN_STAY_MILLIS = 8 * 60 * 1000L
    // Keep a nearby reading from just before the eight-minute cutoff.
    const val STAY_LOOKBACK_MILLIS = MIN_STAY_MILLIS + 2 * 60 * 1000L
    const val STAY_RADIUS_METERS = 150.0
    const val LEAVE_METERS = 200.0
    /** A stop this close to a planned place is that place. */
    const val AUTO_MATCH_METERS = 250.0
    /** Planned places this close are offered as names for an unmatched stop. */
    const val SUGGEST_METERS = 1_000.0

    /**
     * Treat a gap between nearby readings as a stay when intermediate fixes
     * were unavailable.
     */
    fun stayedBetween(previous: LocationFix, current: LocationFix): Boolean =
        current.recordedAt - previous.recordedAt >= MIN_STAY_MILLIS &&
            distanceMeters(previous.latitude, previous.longitude, current.latitude, current.longitude) <= LEAVE_METERS

    /** Readings less precise than this are ignored. */
    const val MAX_ACCURACY_METERS = 100f

    /**
     * The one stop rule shared by trips and Places. [open] is the stop still in
     * progress, [previous] the last kept location before [sample]. [recentStops]
     * and [window] (locations since [STAY_LOOKBACK_MILLIS] before [sample], including
     * it) are only read when needed. [suppressNew] is true right after the user
     * said "Not a stop" here, so a new stop is not made until they move away.
     */
    suspend fun <S : StopSpan> nextStopAction(
        open: S?,
        previous: LocationFix?,
        sample: LocationFix,
        suppressNew: Boolean,
        recentStops: suspend () -> List<S>,
        window: suspend () -> List<LocationFix>,
    ): StopAction<S> {
        if (open != null) {
            val left = distanceMeters(open.latitude, open.longitude, sample.latitude, sample.longitude) > LEAVE_METERS
            return if (left) StopAction.Leave(open, sample.recordedAt) else StopAction.None
        }
        if (suppressNew) return StopAction.None
        if (previous != null && stayedBetween(previous, sample)) {
            // A long stay arrives as several gaps in a row: extend the stop that
            // ended at the previous location instead of starting another one.
            val continuing = recentStops().firstOrNull {
                val departed = it.departedAt
                departed != null && departed >= previous.recordedAt &&
                    distanceMeters(it.latitude, it.longitude, previous.latitude, previous.longitude) <= LEAVE_METERS
            }
            return when {
                continuing == null ->
                    StopAction.Start(previous.latitude, previous.longitude, previous.recordedAt, sample.recordedAt)
                sample.recordedAt > continuing.departedAt!! -> StopAction.Extend(continuing, sample.recordedAt)
                else -> StopAction.None
            }
        }
        val cutoff = sample.recordedAt - MIN_STAY_MILLIS
        val fixes = window().filter { it.accuracyMeters <= MAX_ACCURACY_METERS }
        val anchor = fixes.indexOfLast { it.recordedAt <= cutoff }
        if (anchor < 0 || fixes.size - anchor < 3) return StopAction.None
        val recent = fixes.subList(anchor, fixes.size)
        val centerLat = recent.map { it.latitude }.average()
        val centerLng = recent.map { it.longitude }.average()
        if (recent.any { distanceMeters(centerLat, centerLng, it.latitude, it.longitude) > STAY_RADIUS_METERS }) {
            return StopAction.None
        }
        return StopAction.Start(centerLat, centerLng, recent.first().recordedAt, departedAt = null)
    }

    /** Locations farther than this from every planned place are not part of the trip (e.g. home). */
    const val TRIP_AREA_METERS = 50_000.0

    /**
     * True when a location here belongs to the trip: within [TRIP_AREA_METERS] of
     * any planned place. With no located places there is nothing to judge by, so
     * it is kept rather than silently dropped.
     */
    fun withinTripArea(latitude: Double, longitude: Double, places: List<TripPlaceEntity>): Boolean {
        val located = places.filter { it.latitude != null && it.longitude != null }
        return located.isEmpty() || nearbyPlaces(latitude, longitude, located, TRIP_AREA_METERS).isNotEmpty()
    }

    /** True when [epochMillis] falls on one of the trip's days, in the trip's own time zone. */
    fun withinTripDates(epochMillis: Long, startDate: String, endDate: String, timezone: String): Boolean {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return true
        val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return true
        return !day.isBefore(start) && !day.isAfter(end)
    }

    /** Planned places with coordinates within [maxMeters], nearest first. */
    fun nearbyPlaces(
        latitude: Double,
        longitude: Double,
        places: List<TripPlaceEntity>,
        maxMeters: Double,
    ): List<TripPlaceEntity> = places
        .mapNotNull { place ->
            val lat = place.latitude ?: return@mapNotNull null
            val lng = place.longitude ?: return@mapNotNull null
            place to distanceMeters(latitude, longitude, lat, lng)
        }
        .filter { (_, meters) -> meters <= maxMeters }
        .sortedBy { (_, meters) -> meters }
        .map { (place, _) -> place }

    /** "Near <area>, <city>" from address parts; the area alone is often a house number, so it is skipped. */
    fun approximateName(subLocality: String?, thoroughfare: String?, locality: String?): String? {
        val area = listOf(subLocality, thoroughfare).firstOrNull { !it.isNullOrBlank() && it.any(Char::isLetter) }
        val parts = listOfNotNull(area, locality?.takeIf { it.isNotBlank() }).map { it.trim() }.distinct()
        return if (parts.isEmpty()) null else "Near " + parts.joinToString(", ")
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return earth * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
