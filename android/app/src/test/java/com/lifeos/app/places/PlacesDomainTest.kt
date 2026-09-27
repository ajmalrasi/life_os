package com.lifeos.app.places

import com.lifeos.app.places.data.NameSource
import com.lifeos.app.places.data.PlaceSampleEntity
import com.lifeos.app.places.data.PlaceStopEntity
import com.lifeos.app.places.domain.DaySummary
import com.lifeos.app.places.domain.HomeInference
import com.lifeos.app.places.domain.PlacesTimeline
import com.lifeos.app.places.domain.TimelineEntry
import com.lifeos.app.places.domain.TripStay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PlacesDomainTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val home = 12.9716 to 77.5946
    private val minute = 60_000L

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // 0.001 degrees of latitude is about 111 m.
    private fun sample(time: Long, latOffset: Double, accuracy: Float = 20f, speed: Float? = null) =
        PlaceSampleEntity(
            latitude = home.first + latOffset,
            longitude = home.second,
            accuracyMeters = accuracy,
            speedMetersPerSecond = speed,
            recordedAt = time,
        )

    private fun stop(id: String, from: Long, to: Long?, latOffset: Double = 0.0, name: String = id, source: String = NameSource.ADDRESS) =
        PlaceStopEntity(id, home.first + latOffset, home.second, name, source, from, to)

    @Test
    fun distanceSkipsImpossibleJumpsAndInaccurateFixes() {
        val t = at("2026-09-27T10:00")
        val samples = listOf(
            sample(t, 0.0),
            sample(t + minute, 0.009), // 1 km in a minute: driving
            sample(t + minute + 1_000, 0.5), // 55 km in a second: a bad fix
            sample(t + 2 * minute, 0.018, accuracy = 500f), // too imprecise
            sample(t + 3 * minute, 0.018),
        )
        val movement = DaySummary.of(samples, home = null, zone = zone)
        assertEquals(2_000.0, movement.distanceMeters, 30.0)
        assertEquals(movement.distanceMeters, movement.vehicleMeters, 0.01)
        assertNull(movement.awayMillis)
    }

    @Test
    fun awayTimeCountsOnlyTimeSpentAwayAndSplitsOutLateHours() {
        val samples = listOf(
            sample(at("2026-09-27T21:00"), 0.0),
            sample(at("2026-09-27T21:30"), 0.05),
            sample(at("2026-09-27T23:30"), 0.05),
            sample(at("2026-09-27T23:50"), 0.0),
        )
        val movement = DaySummary.of(samples, home, zone)
        assertEquals(120 * minute, movement.awayMillis)
        assertEquals(90 * minute, movement.lateAwayMillis)
        assertEquals(5_560.0, movement.farthestFromHomeMeters!!, 50.0)
    }

    @Test
    fun lateHoursWrapPastMidnight() {
        assertEquals(60 * minute, DaySummary.lateOverlapMillis(at("2026-09-28T04:00"), at("2026-09-28T06:00"), zone))
        assertEquals(0L, DaySummary.lateOverlapMillis(at("2026-09-28T12:00"), at("2026-09-28T18:00"), zone))
    }

    @Test
    fun homeIsSuggestedFromThreeNightsAtTheSamePlace() {
        val stops = (1..3).map { night ->
            val date = LocalDate.parse("2026-09-2$night")
            stop("n$night", at("${date.minusDays(1)}T22:30"), at("${date}T08:00"), latOffset = 0.0003 * night)
        } + stop("office", at("2026-09-22T10:00"), at("2026-09-22T18:00"), latOffset = 0.1)
        val suggestion = HomeInference.suggest(stops, emptyList(), LocalDate.parse("2026-09-24"), zone, at("2026-09-24T12:00"))
        assertEquals(3, suggestion!!.nights)
        assertEquals(home.first + 0.0006, suggestion.latitude, 0.0001)
    }

    @Test
    fun aDismissedOrAlreadySavedPlaceIsNotSuggested() {
        val stops = (1..3).map { night ->
            val date = LocalDate.parse("2026-09-2$night")
            stop("n$night", at("${date.minusDays(1)}T22:30"), at("${date}T08:00"))
        }
        assertNull(HomeInference.suggest(stops, listOf(home), LocalDate.parse("2026-09-24"), zone, at("2026-09-24T12:00")))
        assertNull(HomeInference.suggest(stops.take(2), emptyList(), LocalDate.parse("2026-09-24"), zone, at("2026-09-24T12:00")))
    }

    @Test
    fun aStayTheTripAlsoRecordedIsShownOnceUnderTheTripName() {
        val placesStop = stop("s", at("2026-10-02T10:00"), at("2026-10-02T11:00"), name = "Near Chandni Chowk, Delhi")
        val trip = TripStay("Red Fort", "Delhi", home.first + 0.001, home.second, at("2026-10-02T10:05"), at("2026-10-02T10:55"))
        val stay = PlacesTimeline.stay(placesStop, listOf(trip))
        assertEquals("Red Fort", stay.name)
        assertEquals("Delhi", stay.tripTitle)
    }

    @Test
    fun theUsersOwnNameBeatsTheTripName() {
        val placesStop = stop("s", at("2026-10-02T10:00"), null, name = "Aunt's place", source = NameSource.USER)
        val trip = TripStay("Hotel", "Delhi", home.first, home.second, at("2026-10-02T10:05"), null)
        assertEquals("Aunt's place", PlacesTimeline.stay(placesStop, listOf(trip)).name)
    }

    @Test
    fun aTripStopElsewhereOrAtAnotherTimeIsNotTheSameStay() {
        val placesStop = stop("s", at("2026-10-02T10:00"), at("2026-10-02T11:00"))
        val later = TripStay("Later", "Delhi", home.first, home.second, at("2026-10-02T12:00"), at("2026-10-02T13:00"))
        val far = TripStay("Far", "Delhi", home.first + 0.01, home.second, at("2026-10-02T10:00"), at("2026-10-02T11:00"))
        assertTrue(!PlacesTimeline.sameStay(placesStop, later) && !PlacesTimeline.sameStay(placesStop, far))
    }

    @Test
    fun timelineAlternatesStaysAndTheMovesBetweenThem() {
        val dayStart = at("2026-09-27T00:00")
        val dayEnd = at("2026-09-28T00:00")
        val stops = listOf(
            stop("home", dayStart, at("2026-09-27T09:00")),
            stop("cafe", at("2026-09-27T09:30"), at("2026-09-27T10:30"), latOffset = 0.03),
        )
        val samples = listOf(
            sample(at("2026-09-27T09:00"), 0.0),
            sample(at("2026-09-27T09:15"), 0.015),
            sample(at("2026-09-27T09:30"), 0.03),
        )
        val entries = PlacesTimeline.build(stops, samples, emptyList(), dayStart, dayEnd, now = dayEnd + 1)
        assertEquals(3, entries.size)
        val move = entries[1] as TimelineEntry.Move
        assertEquals(3_336.0, move.meters, 30.0)
        assertEquals("cafe", (entries[2] as TimelineEntry.Stay).stop.id)
    }
}
