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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        importer.parse("""{"schema_version":2,"trip":{},"days":[]}""")
    }
}
