package com.lifeos.app.trips.importer

import com.lifeos.app.trips.data.ImportedTripBundle
import com.lifeos.app.trips.data.TripActivityEntity
import com.lifeos.app.trips.data.TripDayEntity
import com.lifeos.app.trips.data.TripEntity
import com.lifeos.app.trips.data.TripPlaceEntity
import com.lifeos.app.trips.data.TripReminderEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

@Serializable
data class TripImportDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    val trip: ImportTrip,
    val places: List<ImportPlace> = emptyList(),
    val days: List<ImportDay>,
    val reminders: List<ImportReminder> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<String> = emptyList(),
)

@Serializable
data class ImportTrip(
    val id: String? = null,
    val title: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val timezone: String,
    val currency: String = "INR",
    @SerialName("original_text") val originalText: String? = null,
)

@Serializable
data class ImportPlace(
    val id: String,
    val name: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("google_place_id") val googlePlaceId: String? = null,
    val kind: String = "attraction",
    val verification: String = "unresolved",
)

@Serializable
data class ImportDay(
    val id: String,
    val date: String,
    val title: String,
    val activities: List<ImportActivity> = emptyList(),
)

@Serializable
data class ImportActivity(
    val id: String,
    val title: String,
    val type: String,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("place_id") val placeId: String? = null,
    val notes: String? = null,
    val flexibility: String = "flexible",
    val condition: String? = null,
    @SerialName("transport_mode") val transportMode: String? = null,
    val alternatives: List<ImportAlternative> = emptyList(),
)

@Serializable
data class ImportAlternative(
    val id: String,
    val title: String,
    val condition: String,
    @SerialName("place_id") val placeId: String? = null,
    val notes: String? = null,
    @SerialName("transport_mode") val transportMode: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
)

@Serializable
data class ImportReminder(
    val id: String,
    val title: String,
    val note: String? = null,
    @SerialName("activity_id") val activityId: String? = null,
    @SerialName("local_date_time") val localDateTime: String? = null,
)

data class TripImportPreview(
    val document: TripImportDocument,
    val bundle: ImportedTripBundle,
    val warnings: List<String>,
)

class TripImporter @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun parse(raw: String): TripImportPreview {
        val document = runCatching { json.decodeFromString<TripImportDocument>(raw) }
            .getOrElse { throw IllegalArgumentException("This is not valid LifeOS trip JSON: ${it.message}") }
        require(document.schemaVersion == 1) { "Only trip schema version 1 is supported." }
        require(document.trip.title.isNotBlank()) { "Trip title is required." }
        val start = parseDate(document.trip.startDate, "start_date")
        val end = parseDate(document.trip.endDate, "end_date")
        require(!end.isBefore(start)) { "Trip end_date must be on or after start_date." }
        runCatching { ZoneId.of(document.trip.timezone) }
            .getOrElse { throw IllegalArgumentException("Unknown timezone: ${document.trip.timezone}") }
        require(document.days.isNotEmpty()) { "At least one trip day is required." }

        val allIds = mutableSetOf<String>()
        fun unique(id: String, label: String) {
            require(id.isNotBlank()) { "$label id cannot be blank." }
            require(allIds.add(id)) { "Duplicate id: $id" }
        }
        document.places.forEach { unique(it.id, "Place") }
        document.days.forEach { day ->
            unique(day.id, "Day")
            parseDate(day.date, "day date")
            day.activities.forEach { activity ->
                unique(activity.id, "Activity")
                activity.startTime?.let { parseTime(it, "start_time") }
                activity.endTime?.let { parseTime(it, "end_time") }
                activity.alternatives.forEach { unique(it.id, "Alternative") }
            }
        }
        document.reminders.forEach { unique(it.id, "Reminder") }

        val placeIds = document.places.mapTo(mutableSetOf()) { it.id }
        val activityIds = document.days.flatMap { it.activities }.mapTo(mutableSetOf()) { it.id }
        val warnings = buildList {
            document.days.forEach { day ->
                if (parseDate(day.date, "day date") !in start..end) add("${day.title} is outside the trip dates.")
                day.activities.forEach { activity ->
                    if (activity.placeId != null && activity.placeId !in placeIds) {
                        add("${activity.title} refers to missing place ${activity.placeId}.")
                    }
                    activity.alternatives.forEach { alternative ->
                        if (alternative.placeId != null && alternative.placeId !in placeIds) {
                            add("${alternative.title} refers to missing place ${alternative.placeId}.")
                        }
                    }
                }
            }
            document.reminders.filter { it.activityId != null && it.activityId !in activityIds }
                .forEach { add("Reminder ${it.title} refers to a missing activity.") }
            document.openQuestions.forEach { add("Needs review: $it") }
            document.places.filter { it.latitude == null || it.longitude == null }
                .forEach { add("Resolve ${it.name} on the map before the trip.") }
        }

        val tripId = document.trip.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val activities = mutableListOf<TripActivityEntity>()
        document.days.forEach { day ->
            day.activities.forEachIndexed { index, activity ->
                activities += activity.toEntity(tripId, day.id, index * 10)
                activity.alternatives.forEachIndexed { altIndex, alternative ->
                    activities += TripActivityEntity(
                        id = alternative.id,
                        tripId = tripId,
                        dayId = day.id,
                        title = alternative.title,
                        type = "alternative",
                        durationMinutes = alternative.durationMinutes,
                        placeId = alternative.placeId,
                        notes = alternative.notes,
                        flexibility = "conditional",
                        condition = alternative.condition,
                        transportMode = alternative.transportMode,
                        sortOrder = index * 10 + altIndex + 1,
                    )
                }
            }
        }
        val bundle = ImportedTripBundle(
            trip = TripEntity(
                id = tripId,
                title = document.trip.title.trim(),
                startDate = document.trip.startDate,
                endDate = document.trip.endDate,
                timezone = document.trip.timezone,
                currency = document.trip.currency,
                originalText = document.trip.originalText,
            ),
            days = document.days.mapIndexed { index, day ->
                TripDayEntity(day.id, tripId, day.date, day.title, index)
            },
            places = document.places.map { place ->
                TripPlaceEntity(
                    place.id, tripId, place.name, place.address, place.latitude, place.longitude,
                    place.googlePlaceId, place.kind, place.verification,
                )
            },
            activities = activities,
            reminders = document.reminders.map { reminder ->
                TripReminderEntity(
                    reminder.id, tripId, reminder.activityId, reminder.title,
                    reminder.note, reminder.localDateTime,
                )
            },
        )
        return TripImportPreview(document, bundle, warnings.distinct())
    }

    private fun ImportActivity.toEntity(tripId: String, dayId: String, order: Int) = TripActivityEntity(
        id, tripId, dayId, title, type, startTime, endTime, durationMinutes, placeId,
        notes, flexibility, condition, transportMode, sortOrder = order,
    )

    private fun parseDate(value: String, field: String): LocalDate =
        runCatching { LocalDate.parse(value) }.getOrElse { throw IllegalArgumentException("$field must use YYYY-MM-DD.") }

    private fun parseTime(value: String, field: String): LocalTime =
        runCatching { LocalTime.parse(value) }.getOrElse { throw IllegalArgumentException("$field must use 24-hour HH:MM.") }
}
