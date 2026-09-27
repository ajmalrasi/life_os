package com.lifeos.app.trips.location

import com.lifeos.app.trips.data.TripLocationSampleEntity
import com.lifeos.app.trips.data.TripPlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
