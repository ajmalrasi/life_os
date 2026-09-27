package com.lifeos.app.places.domain

import com.lifeos.app.places.data.PlaceSampleEntity
import com.lifeos.app.trips.location.StopDetection
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * One day's movement, as facts. Nothing here judges whether travel was
 * necessary; that is left to the model, which sees the pattern over weeks.
 * The away-from-home figures are null until Home is saved.
 */
data class DayMovement(
    val distanceMeters: Double,
    /** Distance covered faster than walking or cycling pace, i.e. in a vehicle. */
    val vehicleMeters: Double,
    val awayMillis: Long?,
    /** Away time between 22:00 and 05:00. */
    val lateAwayMillis: Long?,
    val farthestFromHomeMeters: Double?,
)

object DaySummary {
    /** Faster than this between two readings is a bad fix, not travel (250 km/h). */
    const val MAX_PLAUSIBLE_MPS = 70.0
    /** Faster than this is a vehicle (about 25 km/h). */
    const val VEHICLE_MPS = 7.0
    /** Farther than this from Home counts as away. */
    const val AWAY_METERS = 200.0

    fun of(samples: List<PlaceSampleEntity>, home: Pair<Double, Double>?, zone: ZoneId): DayMovement {
        val kept = plausible(samples)
        var distance = 0.0
        var vehicle = 0.0
        var away = 0L
        var lateAway = 0L
        kept.zipWithNext { a, b ->
            val meters = StopDetection.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val seconds = (b.recordedAt - a.recordedAt) / 1000.0
            if (seconds <= 0) return@zipWithNext
            val mps = meters / seconds
            distance += meters
            val reported = b.speedMetersPerSecond?.toDouble()
            if (mps > VEHICLE_MPS || (reported != null && reported > VEHICLE_MPS)) vehicle += meters
            if (home != null && isAway(a, home) && isAway(b, home)) {
                away += b.recordedAt - a.recordedAt
                lateAway += lateOverlapMillis(a.recordedAt, b.recordedAt, zone)
            }
        }
        val farthest = home?.let { (lat, lng) ->
            kept.maxOfOrNull { StopDetection.distanceMeters(lat, lng, it.latitude, it.longitude) }
        }
        return DayMovement(
            distanceMeters = distance,
            vehicleMeters = vehicle,
            awayMillis = home?.let { away },
            lateAwayMillis = home?.let { lateAway },
            farthestFromHomeMeters = farthest,
        )
    }

    /**
     * Precise readings in time order, minus bad fixes: a reading that would mean
     * moving faster than [MAX_PLAUSIBLE_MPS] from the last good one is dropped,
     * so one wild fix doesn't also discard the real movement after it.
     */
    fun plausible(samples: List<PlaceSampleEntity>): List<PlaceSampleEntity> {
        val kept = mutableListOf<PlaceSampleEntity>()
        samples.filter { it.accuracyMeters <= StopDetection.MAX_ACCURACY_METERS }.sortedBy { it.recordedAt }.forEach { b ->
            val a = kept.lastOrNull()
            if (a == null) {
                kept += b
                return@forEach
            }
            val seconds = (b.recordedAt - a.recordedAt) / 1000.0
            val meters = StopDetection.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (seconds > 0 && meters / seconds <= MAX_PLAUSIBLE_MPS) kept += b
        }
        return kept
    }

    /** Distance along the kept readings, skipping impossible jumps. */
    fun pathMeters(samples: List<PlaceSampleEntity>): Double =
        of(samples, home = null, zone = ZoneId.systemDefault()).distanceMeters

    private fun isAway(sample: PlaceSampleEntity, home: Pair<Double, Double>): Boolean =
        StopDetection.distanceMeters(home.first, home.second, sample.latitude, sample.longitude) > AWAY_METERS

    /** How much of [start, end) falls between 22:00 and 05:00 local time. */
    fun lateOverlapMillis(start: Long, end: Long, zone: ZoneId): Long {
        if (end <= start) return 0
        var total = 0L
        var day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().minusDays(1)
        val lastDay = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val nightStart = day.atTime(LocalTime.of(22, 0)).atZone(zone).toInstant().toEpochMilli()
            val nightEnd = day.plusDays(1).atTime(LocalTime.of(5, 0)).atZone(zone).toInstant().toEpochMilli()
            total += (minOf(end, nightEnd) - maxOf(start, nightStart)).coerceAtLeast(0)
            day = day.plusDays(1)
        }
        return total
    }
}
