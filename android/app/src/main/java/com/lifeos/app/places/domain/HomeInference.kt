package com.lifeos.app.places.domain

import com.lifeos.app.trips.location.StopDetection
import com.lifeos.app.trips.location.StopSpan
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Suggests Home from where the user spends their nights; the user always confirms. */
object HomeInference {
    const val CLUSTER_METERS = 150.0
    const val NIGHTS_LOOKED_AT = 7
    const val MIN_NIGHTS = 3
    /** A night counts when a stop covers this much of 00:00 to 05:00. */
    const val MIN_NIGHT_MILLIS = 2 * 60 * 60 * 1000L

    data class Suggestion(val latitude: Double, val longitude: Double, val nights: Int)

    /**
     * The place (stops within [CLUSTER_METERS] of each other) that covered the
     * most of the last [NIGHTS_LOOKED_AT] nights, if at least [MIN_NIGHTS].
     * Places within [CLUSTER_METERS] of [excluded] (Home already saved, or
     * "not home" answers) are never suggested.
     */
    fun suggest(
        stops: List<StopSpan>,
        excluded: List<Pair<Double, Double>>,
        today: LocalDate,
        zone: ZoneId,
        now: Long,
    ): Suggestion? {
        val nights = (0 until NIGHTS_LOOKED_AT).map { today.minusDays(it.toLong()) }
        val clusters = mutableListOf<Cluster>()
        stops.sortedBy { it.arrivedAt }.forEach { stop ->
            val covered = nights.filter { night ->
                val start = night.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = night.atTime(LocalTime.of(5, 0)).atZone(zone).toInstant().toEpochMilli()
                minOf(stop.departedAt ?: now, end) - maxOf(stop.arrivedAt, start) >= MIN_NIGHT_MILLIS
            }
            if (covered.isEmpty()) return@forEach
            val cluster = clusters.firstOrNull { it.distanceTo(stop.latitude, stop.longitude) <= CLUSTER_METERS }
                ?: Cluster().also { clusters += it }
            cluster.points += stop.latitude to stop.longitude
            cluster.nights += covered
        }
        return clusters
            .filter { it.nights.size >= MIN_NIGHTS }
            .filter { cluster -> excluded.none { (lat, lng) -> cluster.distanceTo(lat, lng) <= CLUSTER_METERS } }
            .maxByOrNull { it.nights.size }
            ?.let { Suggestion(it.latitude, it.longitude, it.nights.size) }
    }

    private class Cluster {
        val points = mutableListOf<Pair<Double, Double>>()
        val nights = mutableSetOf<LocalDate>()
        val latitude get() = points.map { it.first }.average()
        val longitude get() = points.map { it.second }.average()
        fun distanceTo(lat: Double, lng: Double) = StopDetection.distanceMeters(latitude, longitude, lat, lng)
    }
}
