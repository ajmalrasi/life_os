package com.lifeos.app.trips.location

import com.lifeos.app.trips.data.TripLocationSampleEntity
import com.lifeos.app.trips.data.TripPlaceEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rules for turning recorded locations into stops and naming them from the plan. */
object StopDetection {
    const val MIN_STAY_MILLIS = 8 * 60 * 1000L
    const val STAY_RADIUS_METERS = 150.0
    const val LEAVE_METERS = 200.0
    /** A stop this close to a planned place is that place. */
    const val AUTO_MATCH_METERS = 250.0
    /** Planned places this close are offered as names for an unmatched stop. */
    const val SUGGEST_METERS = 1_000.0

    /**
     * True when [current] shows the user stayed near [previous]. Tracking asks for
     * an update only after moving 50 m, so standing still produces silence rather
     * than samples: a long gap between two nearby samples is a stay.
     */
    fun stayedBetween(previous: TripLocationSampleEntity, current: TripLocationSampleEntity): Boolean =
        current.recordedAt - previous.recordedAt >= MIN_STAY_MILLIS &&
            distanceMeters(previous.latitude, previous.longitude, current.latitude, current.longitude) <= LEAVE_METERS

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
