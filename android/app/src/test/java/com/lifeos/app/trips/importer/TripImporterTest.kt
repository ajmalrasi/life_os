package com.lifeos.app.trips.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripImporterTest {
    private val importer = TripImporter()

    @Test
    fun preservesConditionalAlternativesAndWarnings() {
        val preview = importer.parse(
            """
            {
              "schema_version": 1,
              "trip": {"title":"Delhi","start_date":"2026-10-01","end_date":"2026-10-02","timezone":"Asia/Kolkata"},
              "places": [{"id":"airport","name":"IGI Airport"}],
              "days": [{"id":"d1","date":"2026-10-01","title":"Arrival","activities":[{
                "id":"land","title":"Land","type":"arrival","start_time":"21:00","place_id":"airport",
                "alternatives":[{"id":"t1","title":"Take a cab","condition":"Terminal is T1"}]
              }]}],
              "open_questions": ["Confirm terminal"]
            }
            """.trimIndent()
        )
        assertEquals(2, preview.bundle.activities.size)
        assertEquals("Terminal is T1", preview.bundle.activities.last().condition)
        assertTrue(preview.warnings.any { "Confirm terminal" in it })
        assertTrue(preview.warnings.any { "Resolve IGI Airport" in it })
    }

    @Test
    fun reScopesChildEntitiesWhenBuildingBundleWithNewId() {
        val preview = importer.parse(
            """
            {
              "schema_version": 1,
              "trip": {"id":"delhi-family-2026","title":"Delhi","start_date":"2026-10-01","end_date":"2026-10-02","timezone":"Asia/Kolkata"},
              "places": [{"id":"airport","name":"IGI Airport"}],
              "days": [{"id":"d1","date":"2026-10-01","title":"Arrival","activities":[{
                "id":"land","title":"Land","type":"arrival","start_time":"21:00","place_id":"airport"
              }]}],
              "reminders": [{"id":"r1","title":"Pickup bags","activity_id":"land"}]
            }
            """.trimIndent()
        )
        assertEquals("delhi-family-2026", preview.originalTripId)

        val newBundle = preview.buildBundle("new-trip-uuid", "Delhi v2")
        assertEquals("new-trip-uuid", newBundle.trip.id)
        assertEquals("Delhi v2", newBundle.trip.title)
        assertEquals("new-trip-uuid_d1", newBundle.days.first().id)
        assertEquals("new-trip-uuid", newBundle.days.first().tripId)
        assertEquals("new-trip-uuid_airport", newBundle.places.first().id)
        assertEquals("new-trip-uuid", newBundle.places.first().tripId)
        assertEquals("new-trip-uuid_land", newBundle.activities.first().id)
        assertEquals("new-trip-uuid_d1", newBundle.activities.first().dayId)
        assertEquals("new-trip-uuid_airport", newBundle.activities.first().placeId)
        assertEquals("new-trip-uuid_r1", newBundle.reminders.first().id)
        assertEquals("new-trip-uuid_land", newBundle.reminders.first().activityId)
    }

    @Test
    fun parsesSchemaV2WithTransitStepsAndEndpoints() {
        val preview = importer.parse(
            """
            {
              "schema_version": 2,
              "trip": {
                "id": "delhi-v2",
                "title": "Delhi Trip v2",
                "start_date": "2026-10-01",
                "end_date": "2026-10-02",
                "timezone": "Asia/Kolkata"
              },
              "places": [
                {"id": "airport", "name": "IGI Airport", "kind": "airport"},
                {"id": "hotel", "name": "Connaught Hotel", "kind": "hotel"}
              ],
              "days": [
                {
                  "id": "d1",
                  "date": "2026-10-01",
                  "title": "Arrival",
                  "activities": [
                    {
                      "id": "transfer",
                      "title": "Airport to Hotel Journey",
                      "type": "transport",
                      "start_time": "20:00",
                      "end_time": "21:15",
                      "duration_minutes": 75,
                      "from_place_id": "airport",
                      "to_place_id": "hotel",
                      "steps": [
                        {
                          "step_order": 1,
                          "mode": "metro",
                          "title": "Orange Line Express",
                          "duration_minutes": 25,
                          "departure": {
                            "station_name": "IGI Airport",
                            "terminal": "T3",
                            "platform": "Platform 1"
                          },
                          "arrival": {
                            "station_name": "New Delhi Metro Station",
                            "platform": "Platform 2",
                            "exit_gate": "Gate 2"
                          },
                          "transit_details": {
                            "line_name": "Airport Express",
                            "line_color": "Orange",
                            "direction": "Towards New Delhi",
                            "stops_count": 3
                          }
                        },
                        {
                          "step_order": 2,
                          "mode": "car",
                          "title": "Uber to Hotel",
                          "duration_minutes": 15,
                          "departure": {
                            "pickup_location": "Ajmeri Gate Uber Zone"
                          },
                          "arrival": {
                            "dropoff_location": "Hotel Lobby"
                          },
                          "transit_details": {
                            "road_type": "rideshare",
                            "service_provider": "Uber Premier",
                            "fare": {"amount": "150", "currency": "INR"}
                          }
                        }
                      ],
                      "alternatives": [
                        {
                          "id": "alt-cab",
                          "title": "Direct Cab from T1",
                          "condition": "Arriving at T1",
                          "from_place_id": "airport",
                          "to_place_id": "hotel",
                          "steps": [
                            {
                              "step_order": 1,
                              "mode": "car",
                              "title": "Prepaid Taxi",
                              "departure": {"terminal": "T1", "pickup_location": "T1 Taxi Lane"}
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, preview.bundle.activities.size)
        val mainTransfer = preview.bundle.activities.first()
        assertEquals("new-trip-id_airport", mainTransfer.fromPlaceId?.replace(preview.bundle.trip.id, "new-trip-id"))
        assertEquals("new-trip-id_hotel", mainTransfer.toPlaceId?.replace(preview.bundle.trip.id, "new-trip-id"))
        assertNotNull(mainTransfer.stepsJson)
        assertTrue(mainTransfer.stepsJson!!.contains("Orange Line Express"))
        assertTrue(mainTransfer.stepsJson!!.contains("Platform 1"))
        assertTrue(mainTransfer.stepsJson!!.contains("Ajmeri Gate Uber Zone"))

        val alternative = preview.bundle.activities.last()
        assertNotNull(alternative.stepsJson)
        assertTrue(alternative.stepsJson!!.contains("Prepaid Taxi"))
        assertTrue(alternative.stepsJson!!.contains("T1 Taxi Lane"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        importer.parse("""{"schema_version":99,"trip":{},"days":[]}""")
    }
}
