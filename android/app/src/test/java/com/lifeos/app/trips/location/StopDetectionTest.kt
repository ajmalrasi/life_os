package com.lifeos.app.trips.location

import com.lifeos.app.trips.data.TripLocationSampleEntity
import com.lifeos.app.trips.data.TripPlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StopDetectionTest {
    // Roughly 0.001 degrees of latitude = 111 m.
    private fun sample(minute: Int, latOffset: Double = 0.0) = TripLocationSampleEntity(
        tripId = "t",
        latitude = 28.6139 + latOffset,
        longitude = 77.2090,
        accuracyMeters = 20f,
        speedMetersPerSecond = null,
        recordedAt = minute * 60_000L,
    )

    private fun place(id: String, latOffset: Double?) = TripPlaceEntity(
        id = id,
        tripId = "t",
        name = id,
        latitude = latOffset?.let { 28.6139 + it },
        longitude = latOffset?.let { 77.2090 },
    )

    @Test
    fun longSilenceNearTheSameSpotIsAStay() {
        assertTrue(StopDetection.stayedBetween(sample(0), sample(20, latOffset = 0.0008)))
    }

    @Test
    fun shortGapIsNotAStay() {
        assertFalse(StopDetection.stayedBetween(sample(0), sample(5, latOffset = 0.0005)))
    }

    @Test
    fun longGapFarAwayIsTravelNotAStay() {
        assertFalse(StopDetection.stayedBetween(sample(0), sample(30, latOffset = 0.01)))
    }

    @Test
    fun nearbyPlacesAreNearestFirstAndSkipUnresolvedOrFar() {
        val places = listOf(
            place("far", 0.02),
            place("unresolved", null),
            place("second", 0.004),
            place("nearest", 0.001),
        )
        val nearby = StopDetection.nearbyPlaces(28.6139, 77.2090, places, StopDetection.SUGGEST_METERS)
        assertEquals(listOf("nearest", "second"), nearby.map { it.id })
    }

    @Test
    fun autoMatchOnlyWithinTheMatchRadius() {
        val places = listOf(place("close", 0.002), place("outside", 0.003))
        val matched = StopDetection.nearbyPlaces(28.6139, 77.2090, places, StopDetection.AUTO_MATCH_METERS)
        assertEquals(listOf("close"), matched.map { it.id })
    }

    @Test
    fun approximateNameUsesAreaAndCity() {
        assertEquals("Near Hauz Khas, New Delhi", StopDetection.approximateName("Hauz Khas", "Aurobindo Marg", "New Delhi"))
    }

    @Test
    fun approximateNameSkipsHouseNumbersAndRepeats() {
        assertEquals("Near New Delhi", StopDetection.approximateName(null, "12/4", "New Delhi"))
        assertEquals("Near Connaught Place", StopDetection.approximateName("Connaught Place", null, "Connaught Place"))
        assertEquals(null, StopDetection.approximateName(null, "  ", null))
    }

    @Test
    fun stopsFarFromEveryPlannedPlaceAreOutsideTheTrip() {
        val places = listOf(place("hotel", 0.0), place("unresolved", null))
        // ~33 km and ~1,000 km north of the hotel.
        assertTrue(StopDetection.withinTripArea(28.6139 + 0.3, 77.2090, places))
        assertFalse(StopDetection.withinTripArea(28.6139 + 9.0, 77.2090, places))
    }

    @Test
    fun withoutLocatedPlacesEveryStopIsKept() {
        assertTrue(StopDetection.withinTripArea(10.0, 76.0, listOf(place("unresolved", null))))
    }

    @Test
    fun onlyTheTripsOwnDaysCountInItsTimeZone() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        fun at(text: String) = java.time.LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        val inTrip = { t: Long -> StopDetection.withinTripDates(t, "2026-10-01", "2026-10-05", "Asia/Kolkata") }
        assertFalse(inTrip(at("2026-09-30T23:59")))
        assertTrue(inTrip(at("2026-10-01T00:00")))
        assertTrue(inTrip(at("2026-10-05T23:59")))
        assertFalse(inTrip(at("2026-10-06T00:00")))
    }

    private data class Span(
        override val latitude: Double,
        override val longitude: Double,
        override val arrivedAt: Long,
        override val departedAt: Long?,
    ) : StopSpan

    private fun span(fromMinute: Int, toMinute: Int?, latOffset: Double = 0.0) =
        Span(28.6139 + latOffset, 77.2090, fromMinute * 60_000L, toMinute?.let { it * 60_000L })

    private fun act(
        open: Span? = null,
        previous: LocationFix? = null,
        current: LocationFix,
        suppress: Boolean = false,
        stops: List<Span> = emptyList(),
        window: List<LocationFix> = emptyList(),
    ) = runBlocking { StopDetection.nextStopAction(open, previous, current, suppress, { stops }, { window }) }

    @Test
    fun movingAwayFromTheOpenStopLeavesIt() {
        val open = span(0, null)
        assertEquals(StopAction.Leave(open, 30 * 60_000L), act(open = open, current = sample(30, latOffset = 0.003)))
    }

    @Test
    fun stayingNearTheOpenStopChangesNothing() {
        assertEquals(StopAction.None, act(open = span(0, null), current = sample(30, latOffset = 0.001)))
    }

    @Test
    fun aLongSilenceStartsAClosedStopAtThePreviousLocation() {
        val action = act(previous = sample(0), current = sample(20, latOffset = 0.0005))
        assertEquals(StopAction.Start(28.6139, 77.2090, 0L, 20 * 60_000L), action)
    }

    @Test
    fun aSecondSilenceExtendsTheStopThatEndedThere() {
        val earlier = span(0, 20)
        val action = act(previous = sample(20), current = sample(45), stops = listOf(earlier))
        assertEquals(StopAction.Extend(earlier, 45 * 60_000L), action)
    }

    @Test
    fun notAStopSuppressesNewStopsUntilTheUserMoves() {
        assertEquals(StopAction.None, act(previous = sample(0), current = sample(20), suppress = true))
    }

    @Test
    fun tightClusterOverEightMinutesStartsAnOpenStop() {
        val window = listOf(sample(0), sample(4, 0.0003), sample(9, 0.0001))
        val action = act(previous = window[1], current = window[2], window = window)
        assertTrue(action is StopAction.Start && action.departedAt == null && action.arrivedAt == 0L)
    }

    @Test
    fun stationaryFixesConfirmAStopBeforeTheCarDrivesAway() {
        val window = listOf(sample(0, 0.01), sample(1), sample(4), sample(7), sample(10))
        val action = act(previous = window[3], current = window[4], window = window)
        assertEquals(StopAction.Start(28.6139, 77.2090, 60_000L, null), action)
    }

    @Test
    fun stationaryFixesUnderEightMinutesDoNotStartAStop() {
        val window = listOf(sample(0), sample(3), sample(7))
        assertEquals(StopAction.None, act(previous = window[1], current = window[2], window = window))
    }

    @Test
    fun spreadOutWindowIsTravel() {
        val window = listOf(sample(0), sample(4, 0.003), sample(9, 0.006))
        assertEquals(StopAction.None, act(previous = window[1], current = window[2], window = window))
    }
}
