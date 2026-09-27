package com.lifeos.app.places.domain

import com.lifeos.app.places.data.NameSource
import com.lifeos.app.places.data.PlaceSampleEntity
import com.lifeos.app.places.data.PlaceStopEntity
import com.lifeos.app.trips.location.StopDetection

/** A stop recorded by a trip, used so the same stay is shown once, under the trip's name. */
data class TripStay(
    val name: String,
    val tripTitle: String,
    val latitude: Double,
    val longitude: Double,
    val arrivedAt: Long,
    val departedAt: Long?,
)

sealed interface TimelineEntry {
    data class Stay(
        val stop: PlaceStopEntity,
        val name: String,
        /** Set when a trip recorded the same stay. */
        val tripTitle: String?,
    ) : TimelineEntry

    data class Move(val from: Long, val to: Long, val meters: Double, val ongoing: Boolean) : TimelineEntry
}

object PlacesTimeline {
    /** Shorter movement than this before the first or after the last stop is GPS drift. */
    const val MIN_EDGE_MOVE_METERS = 100.0

    /**
     * The day as stays and the moves between them. [samples] and [stops] are the
     * day's; a stay a trip also recorded takes the trip's name unless the user
     * named it or it is Home/Work.
     */
    fun build(
        stops: List<PlaceStopEntity>,
        samples: List<PlaceSampleEntity>,
        tripStays: List<TripStay>,
        dayStart: Long,
        dayEnd: Long,
        now: Long,
    ): List<TimelineEntry> {
        val sorted = stops.sortedBy { it.arrivedAt }
        val entries = mutableListOf<TimelineEntry>()
        fun move(from: Long, to: Long, ongoing: Boolean, minMeters: Double) {
            if (to <= from) return
            val meters = DaySummary.pathMeters(samples.filter { it.recordedAt in from..to })
            if (meters >= minMeters) entries += TimelineEntry.Move(from, to, meters, ongoing)
        }
        if (sorted.isEmpty()) {
            val last = samples.maxOfOrNull { it.recordedAt }
            if (last != null) move(dayStart, last, ongoing = dayEnd > now, minMeters = MIN_EDGE_MOVE_METERS)
            return entries
        }
        move(dayStart, sorted.first().arrivedAt, ongoing = false, minMeters = MIN_EDGE_MOVE_METERS)
        sorted.forEachIndexed { index, stop ->
            entries += stay(stop, tripStays)
            val departed = stop.departedAt ?: return@forEachIndexed
            val next = sorted.getOrNull(index + 1)
            if (next != null) {
                move(departed, next.arrivedAt, ongoing = false, minMeters = 0.0)
            } else {
                val lastSample = samples.maxOfOrNull { it.recordedAt } ?: return@forEachIndexed
                move(departed, lastSample, ongoing = dayEnd > now, minMeters = MIN_EDGE_MOVE_METERS)
            }
        }
        return entries
    }

    fun stay(stop: PlaceStopEntity, tripStays: List<TripStay>): TimelineEntry.Stay {
        val trip = tripStays.firstOrNull { sameStay(stop, it) }
        val keepOwnName = stop.nameSource == NameSource.USER || stop.nameSource == NameSource.SAVED
        return TimelineEntry.Stay(
            stop = stop,
            name = if (keepOwnName || trip == null) stop.name else trip.name,
            tripTitle = trip?.tripTitle,
        )
    }

    /** Overlapping in time and within the trip's own "this is that place" radius. */
    fun sameStay(stop: PlaceStopEntity, trip: TripStay): Boolean {
        val overlaps = stop.arrivedAt <= (trip.departedAt ?: Long.MAX_VALUE) &&
            trip.arrivedAt <= (stop.departedAt ?: Long.MAX_VALUE)
        return overlaps && StopDetection.distanceMeters(stop.latitude, stop.longitude, trip.latitude, trip.longitude) <=
            StopDetection.AUTO_MATCH_METERS
    }
}
