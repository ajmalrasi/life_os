package com.lifeos.app.trips.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeos.app.trips.importer.TripImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class TripDatabaseTest {
    private lateinit var db: TripDatabase
    private lateinit var dao: TripDao
    private val importer = TripImporter()

    private val sampleJsonV1 = """
        {
          "schema_version": 1,
          "trip": {
            "id": "delhi-family-2026",
            "title": "Delhi family trip",
            "start_date": "2026-10-01",
            "end_date": "2026-10-05",
            "timezone": "Asia/Kolkata",
            "currency": "INR"
          },
          "places": [
            { "id": "igi", "name": "IGI Airport", "kind": "airport" },
            { "id": "aerocity", "name": "Aerocity", "kind": "area" }
          ],
          "days": [
            {
              "id": "d1",
              "date": "2026-10-01",
              "title": "Arrival",
              "activities": [
                { "id": "act1", "title": "Land at Delhi", "type": "arrival", "place_id": "igi" }
              ]
            }
          ],
          "reminders": [
            { "id": "rem1", "title": "Get taxi", "activity_id": "act1" }
          ]
        }
    """.trimIndent()

    private val sampleJsonV2 = """
        {
          "schema_version": 1,
          "trip": {
            "id": "delhi-family-2026",
            "title": "Delhi family trip v2",
            "start_date": "2026-10-01",
            "end_date": "2026-10-05",
            "timezone": "Asia/Kolkata",
            "currency": "INR"
          },
          "places": [
            { "id": "red-fort", "name": "Red Fort", "kind": "attraction" },
            { "id": "qutub", "name": "Qutub Minar", "kind": "attraction" }
          ],
          "days": [
            {
              "id": "d1",
              "date": "2026-10-01",
              "title": "Sightseeing",
              "activities": [
                { "id": "act_rf", "title": "Visit Red Fort", "type": "visit", "place_id": "red-fort" }
              ]
            }
          ],
          "reminders": []
        }
    """.trimIndent()

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TripDatabase::class.java).build()
        dao = db.tripDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testAddAsNewTripCreatesSeparateTripWithoutCollidingOrMerging() = runBlocking {
        // 1. Import V1
        val preview1 = importer.parse(sampleJsonV1)
        val bundle1 = preview1.buildBundle(targetTripId = "trip-v1", title = "Delhi V1")
        dao.importTrip(bundle1)

        val detail1 = dao.getTrip("trip-v1")
        assertNotNull(detail1)
        assertEquals(2, detail1!!.places.size)
        assertEquals(1, detail1.activities.size)

        // 2. Import V2 as a NEW trip (using different tripId even if JSON had delhi-family-2026)
        val preview2 = importer.parse(sampleJsonV2)
        val bundle2 = preview2.buildBundle(targetTripId = "trip-v2", title = "Delhi V2")
        dao.importTrip(bundle2)

        // Verify both trips exist independently
        val trips = dao.observeTrips().first()
        assertEquals(2, trips.size)

        val loadedV1 = dao.getTrip("trip-v1")!!
        val loadedV2 = dao.getTrip("trip-v2")!!

        assertEquals("Delhi V1", loadedV1.trip.title)
        assertEquals(2, loadedV1.places.size)
        assertTrue(loadedV1.places.any { it.name == "IGI Airport" })

        assertEquals("Delhi V2", loadedV2.trip.title)
        assertEquals(2, loadedV2.places.size)
        assertTrue(loadedV2.places.any { it.name == "Red Fort" })
    }

    @Test
    fun testReplaceTripItineraryCleansOldPlacesAndActivities() = runBlocking {
        // 1. Import V1
        val preview1 = importer.parse(sampleJsonV1)
        val bundle1 = preview1.buildBundle(targetTripId = "delhi-trip", title = "Delhi Initial")
        dao.importTrip(bundle1)

        val detail1 = dao.getTrip("delhi-trip")!!
        assertEquals(2, detail1.places.size)
        assertTrue(detail1.places.any { it.name == "IGI Airport" })

        // 2. Replace trip itinerary with V2
        val preview2 = importer.parse(sampleJsonV2)
        val bundle2 = preview2.buildBundle(targetTripId = "delhi-trip", title = "Delhi Replaced")
        dao.replaceTripItinerary(bundle2)

        val replaced = dao.getTrip("delhi-trip")!!
        assertEquals("Delhi Replaced", replaced.trip.title)
        // Old places (IGI, Aerocity) should NOT linger:
        assertEquals(2, replaced.places.size)
        assertTrue(replaced.places.none { it.name == "IGI Airport" })
        assertTrue(replaced.places.any { it.name == "Red Fort" })
        assertTrue(replaced.places.any { it.name == "Qutub Minar" })
    }

    @Test
    fun testDeleteTripCascadesAndRemovesEverything() = runBlocking {
        val preview = importer.parse(sampleJsonV1)
        val bundle = preview.buildBundle(targetTripId = "trip-to-delete", title = "To Delete")
        dao.importTrip(bundle)

        assertNotNull(dao.getTrip("trip-to-delete"))

        // Delete the trip
        dao.deleteTrip("trip-to-delete")

        assertNull(dao.getTrip("trip-to-delete"))
        val remainingTrips = dao.observeTrips().first()
        assertTrue(remainingTrips.none { it.id == "trip-to-delete" })
    }
}
