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

    @Test
    fun testSchemaV2MultiStepTransitPersistsInDatabase() = runBlocking {
        val sampleV2 = """
            {
              "schema_version": 2,
              "trip": {
                "id": "transit-test",
                "title": "Transit Trip",
                "start_date": "2026-10-01",
                "end_date": "2026-10-02",
                "timezone": "Asia/Kolkata"
              },
              "places": [
                { "id": "igi", "name": "IGI Metro", "kind": "metro_station" },
                { "id": "ndls", "name": "New Delhi Metro", "kind": "metro_station" }
              ],
              "days": [
                {
                  "id": "d1",
                  "date": "2026-10-01",
                  "title": "Arrival",
                  "activities": [
                    {
                      "id": "metro_journey",
                      "title": "Airport Express",
                      "type": "transport",
                      "from_place_id": "igi",
                      "to_place_id": "ndls",
                      "steps": [
                        {
                          "step_order": 1,
                          "mode": "metro",
                          "title": "Orange Line Express",
                          "duration_minutes": 22,
                          "departure": {
                            "station_name": "IGI Airport",
                            "terminal": "T3",
                            "platform": "Platform 1"
                          },
                          "arrival": {
                            "station_name": "New Delhi",
                            "platform": "Platform 2"
                          },
                          "transit_details": {
                            "line_name": "Airport Express Line",
                            "direction": "Towards New Delhi"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val preview = importer.parse(sampleV2)
        val bundle = preview.buildBundle("transit-test", "Transit Trip")
        dao.importTrip(bundle)

        val detail = dao.getTrip("transit-test")
        assertNotNull(detail)
        assertEquals(1, detail!!.activities.size)
        val act = detail.activities.first()
        assertEquals("transit-test_igi", act.fromPlaceId)
        assertEquals("transit-test_ndls", act.toPlaceId)
        assertNotNull(act.stepsJson)
        assertTrue(act.stepsJson!!.contains("Orange Line Express"))
        assertTrue(act.stepsJson!!.contains("Platform 1"))
    }

    @Test
    fun testSeedExampleTripV2ToDeviceDatabase() = runBlocking {
        val appDb = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TripDatabase::class.java,
            "lifeos-trips.db",
        ).addMigrations(TripDatabase.MIGRATION_1_2).build()

        val sampleV2 = """
            {
              "schema_version": 2,
              "trip": {
                "id": "delhi-agra-2026",
                "title": "Delhi & Agra Explorer",
                "start_date": "2026-10-01",
                "end_date": "2026-10-04",
                "timezone": "Asia/Kolkata",
                "currency": "INR",
                "original_text": "Flight arrival at Delhi IGI, metro transfer, hotel stay, early morning Shatabdi train to Agra, and local travel."
              },
              "places": [
                {
                  "id": "del-airport",
                  "name": "IGI Airport (DEL)",
                  "kind": "airport",
                  "station_code": "DEL",
                  "city": "New Delhi"
                },
                {
                  "id": "igi-metro-station",
                  "name": "IGI Airport Metro Station",
                  "kind": "metro_station",
                  "city": "New Delhi"
                },
                {
                  "id": "ndls-metro-station",
                  "name": "New Delhi Metro Station",
                  "kind": "metro_station",
                  "city": "New Delhi"
                },
                {
                  "id": "ndls-railway-station",
                  "name": "New Delhi Railway Station (NDLS)",
                  "kind": "railway_station",
                  "station_code": "NDLS",
                  "city": "New Delhi"
                },
                {
                  "id": "connaught-hotel",
                  "name": "The Lalit New Delhi",
                  "kind": "hotel",
                  "city": "New Delhi"
                },
                {
                  "id": "agra-cantt-station",
                  "name": "Agra Cantt Railway Station (AGC)",
                  "kind": "railway_station",
                  "station_code": "AGC",
                  "city": "Agra"
                },
                {
                  "id": "taj-mahal",
                  "name": "Taj Mahal",
                  "kind": "monument",
                  "city": "Agra"
                }
              ],
              "days": [
                {
                  "id": "day-1",
                  "date": "2026-10-01",
                  "title": "Arrival & City Transfer",
                  "activities": [
                    {
                      "id": "act-journey-to-hotel",
                      "title": "Metro + Auto to Hotel",
                      "type": "transport",
                      "start_time": "19:30",
                      "end_time": "20:20",
                      "duration_minutes": 50,
                      "from_place_id": "igi-metro-station",
                      "to_place_id": "connaught-hotel",
                      "steps": [
                        {
                          "step_order": 1,
                          "mode": "metro",
                          "title": "Airport Express Line to Shivaji Stadium",
                          "duration_minutes": 18,
                          "departure": {
                            "station_name": "IGI Airport Metro Station",
                            "terminal": "T3",
                            "platform": "Platform 1",
                            "gate": "Gate 4",
                            "scheduled_time": "19:35"
                          },
                          "arrival": {
                            "station_name": "Shivaji Stadium Metro Station",
                            "platform": "Platform 2",
                            "exit_gate": "Gate 1 (Connaught Place side)",
                            "scheduled_time": "19:53"
                          },
                          "transit_details": {
                            "line_name": "Airport Express Line",
                            "line_color": "Orange",
                            "direction": "Towards New Delhi",
                            "stops_count": 2,
                            "intermediate_stops": ["Delhi Aerocity", "Dhaula Kuan"],
                            "frequency_minutes": 10,
                            "interchange_notes": "For Blue line, continue 1 more stop to New Delhi",
                            "fare": { "amount": "50", "currency": "INR" }
                          },
                          "instructions": "Follow orange floor markings from T3 arrivals to Metro Express entrance."
                        },
                        {
                          "step_order": 2,
                          "mode": "car",
                          "title": "Auto-rickshaw to Hotel",
                          "duration_minutes": 12,
                          "departure": {
                            "station_name": "Shivaji Stadium Exit",
                            "pickup_location": "Auto Stand at Baba Kharak Singh Marg",
                            "scheduled_time": "19:58"
                          },
                          "arrival": {
                            "name": "The Lalit Hotel",
                            "dropoff_location": "Main Lobby Entrance",
                            "scheduled_time": "20:10"
                          },
                          "transit_details": {
                            "road_type": "auto_rickshaw",
                            "route_name": "Via Barakhamba Road",
                            "fare": { "amount": "70", "currency": "INR" }
                          }
                        }
                      ]
                    },
                    {
                      "id": "act-hotel-checkin",
                      "title": "Check in at The Lalit",
                      "type": "rest",
                      "start_time": "20:30",
                      "place_id": "connaught-hotel"
                    }
                  ]
                },
                {
                  "id": "day-2",
                  "date": "2026-10-02",
                  "title": "Day Trip to Agra",
                  "activities": [
                    {
                      "id": "act-train-to-agra",
                      "title": "Bhopal Shatabdi Express to Agra",
                      "type": "transport",
                      "start_time": "06:00",
                      "end_time": "07:50",
                      "duration_minutes": 110,
                      "from_place_id": "ndls-railway-station",
                      "to_place_id": "agra-cantt-station",
                      "flexibility": "fixed",
                      "steps": [
                        {
                          "step_order": 1,
                          "mode": "car",
                          "title": "Cab from Hotel to NDLS Station",
                          "duration_minutes": 15,
                          "departure": {
                            "name": "The Lalit Hotel",
                            "pickup_location": "Hotel Porch",
                            "scheduled_time": "05:15"
                          },
                          "arrival": {
                            "station_name": "New Delhi Railway Station",
                            "dropoff_location": "Paharganj side gate",
                            "scheduled_time": "05:30"
                          },
                          "transit_details": {
                            "road_type": "taxi",
                            "service_provider": "Hotel Travel Desk",
                            "fare": { "amount": "250", "currency": "INR" }
                          }
                        },
                        {
                          "step_order": 2,
                          "mode": "train",
                          "title": "Train #12002 Bhopal Shatabdi Express",
                          "duration_minutes": 110,
                          "distance_km": 195.0,
                          "departure": {
                            "station_name": "New Delhi Railway Station",
                            "station_code": "NDLS",
                            "platform": "Platform 1",
                            "scheduled_time": "06:00"
                          },
                          "arrival": {
                            "station_name": "Agra Cantt Railway Station",
                            "station_code": "AGC",
                            "platform": "Platform 1",
                            "scheduled_time": "07:50",
                            "exit_gate": "Main Exit Gate"
                          },
                          "transit_details": {
                            "train_number": "12002",
                            "train_name": "Bhopal Shatabdi Express",
                            "operator": "Indian Railways",
                            "coach": "C2",
                            "seat_or_berth": "Seat 45 (Window)",
                            "booking_class": "AC Chair Car (CC)",
                            "pnr": "284-9182741",
                            "fare": { "amount": "755", "currency": "INR" },
                            "notes": "Morning tea and breakfast served on board."
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val preview = importer.parse(sampleV2)
        val bundle = preview.buildBundle("delhi-agra-2026", "Delhi & Agra Explorer")
        appDb.tripDao().importTrip(bundle)
        appDb.close()
    }
}
