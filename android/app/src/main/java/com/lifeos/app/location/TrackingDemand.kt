package com.lifeos.app.location

/**
 * Who wants locations right now. One service serves both, so a tracked trip
 * with Places on is still one GPS request and one notification.
 */
data class TrackingDemand(val places: Boolean, val tripId: String?) {
    val shouldRun: Boolean get() = places || tripId != null

    companion object {
        val NONE = TrackingDemand(places = false, tripId = null)

        fun of(placesEnabled: Boolean, hasLocationPermission: Boolean, activeTripId: String?): TrackingDemand =
            if (!hasLocationPermission) NONE else TrackingDemand(placesEnabled, activeTripId)
    }
}

/** The ongoing notification for a [TrackingDemand]. */
data class TrackingNotice(val title: String, val text: String, val pauseTrip: Boolean, val pausePlaces: Boolean) {
    companion object {
        /** [tripWaiting] is the trip's "waiting / ended" line, or null while it is recording. */
        fun of(demand: TrackingDemand, tripTitle: String?, tripWaiting: String?): TrackingNotice {
            val trip = demand.tripId != null
            val tripName = tripTitle?.takeIf { it.isNotBlank() } ?: "trip"
            return when {
                trip && demand.places -> TrackingNotice(
                    title = "Recording $tripName · noting places",
                    text = tripWaiting?.let { "$it Places is still noting where you go." }
                        ?: "Recording your route and stops for the trip and Places.",
                    pauseTrip = true,
                    pausePlaces = true,
                )
                trip -> TrackingNotice(
                    title = if (tripWaiting == null) "Trip tracking is active" else "Trip tracking is on",
                    text = tripWaiting ?: "LifeOS is recording your route and visited stops.",
                    pauseTrip = true,
                    pausePlaces = false,
                )
                else -> TrackingNotice(
                    title = "Noting places",
                    text = "LifeOS notes where you stop, to link places with spending.",
                    pauseTrip = false,
                    pausePlaces = true,
                )
            }
        }
    }
}

/**
 * Which trip the one service should follow. At most one trip records: the one
 * already running if still active, else the first active trip that can record.
 * Every other active trip is paused.
 */
data class TripChoice(val follow: String?, val pause: List<String>) {
    companion object {
        fun of(activeTripIds: List<String>, runningTripId: String?, canRecord: (String) -> Boolean): TripChoice {
            val follow = activeTripIds.firstOrNull { it == runningTripId }
                ?: activeTripIds.firstOrNull { it != runningTripId && canRecord(it) }
            return TripChoice(follow, activeTripIds.filter { it != follow })
        }
    }
}
