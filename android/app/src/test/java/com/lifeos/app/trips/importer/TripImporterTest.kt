package com.lifeos.app.trips.importer

import org.junit.Assert.assertEquals
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        importer.parse("""{"schema_version":2,"trip":{},"days":[]}""")
    }
}
