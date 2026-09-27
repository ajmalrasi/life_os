package com.lifeos.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingDemandTest {
    @Test
    fun placesAndTripShareOneRun() {
        val demand = TrackingDemand.of(placesEnabled = true, hasLocationPermission = true, activeTripId = "delhi")
        assertEquals(TrackingDemand(places = true, tripId = "delhi"), demand)
        assertTrue(demand.shouldRun)
    }

    @Test
    fun placesOffStillRecordsTheTrip() {
        val demand = TrackingDemand.of(placesEnabled = false, hasLocationPermission = true, activeTripId = "delhi")
        assertEquals(TrackingDemand(places = false, tripId = "delhi"), demand)
        assertTrue(demand.shouldRun)
    }

    @Test
    fun noTripKeepsPlacesRunning() {
        assertEquals(
            TrackingDemand(places = true, tripId = null),
            TrackingDemand.of(placesEnabled = true, hasLocationPermission = true, activeTripId = null),
        )
    }

    @Test
    fun nothingRunsWithoutPermissionOrDemand() {
        assertFalse(TrackingDemand.of(true, hasLocationPermission = false, activeTripId = "delhi").shouldRun)
        assertFalse(TrackingDemand.of(false, hasLocationPermission = true, activeTripId = null).shouldRun)
    }

    @Test
    fun bothShowsOneNoticeWithBothPauseActions() {
        val notice = TrackingNotice.of(TrackingDemand(true, "delhi"), tripTitle = "Delhi", tripWaiting = null)
        assertEquals("Recording Delhi · noting places", notice.title)
        assertTrue(notice.pauseTrip && notice.pausePlaces)
    }

    @Test
    fun tripOnlyNoticeKeepsTheTripWording() {
        val waiting = "Waiting for the trip. Recording starts Thu, 1 Oct."
        val notice = TrackingNotice.of(TrackingDemand(false, "delhi"), "Delhi", waiting)
        assertEquals("Trip tracking is on", notice.title)
        assertEquals(waiting, notice.text)
        assertTrue(notice.pauseTrip)
        assertFalse(notice.pausePlaces)
    }

    @Test
    fun placesOnlyNoticeCanOnlyPausePlaces() {
        val notice = TrackingNotice.of(TrackingDemand(true, null), null, null)
        assertEquals("Noting places", notice.title)
        assertFalse(notice.pauseTrip)
        assertTrue(notice.pausePlaces)
    }

    @Test
    fun theRunningTripKeepsRecordingAndOthersPause() {
        val choice = TripChoice.of(listOf("a", "b"), runningTripId = "b") { true }
        assertEquals("b", choice.follow)
        assertEquals(listOf("a"), choice.pause)
    }

    @Test
    fun afterARestartTheFirstTripThatCanRecordResumes() {
        val choice = TripChoice.of(listOf("ended", "current"), runningTripId = null) { it == "current" }
        assertEquals("current", choice.follow)
        assertEquals(listOf("ended"), choice.pause)
    }

    @Test
    fun withoutPermissionEveryActiveTripPauses() {
        val choice = TripChoice.of(listOf("a"), runningTripId = null) { false }
        assertEquals(null, choice.follow)
        assertEquals(listOf("a"), choice.pause)
    }
}
