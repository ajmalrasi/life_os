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
) {
    val originalTripId: String? get() = document.trip.id?.takeIf { it.isNotBlank() }

    fun buildBundle(targetTripId: String, title: String? = null): ImportedTripBundle =
        TripImporter.buildBundle(document, targetTripId, title)
}

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
        val bundle = buildBundle(document, tripId)
        return TripImportPreview(document, bundle, warnings.distinct())
    }

    companion object {
        fun buildBundle(
            document: TripImportDocument,
            targetTripId: String,
            targetTitle: String? = null,
        ): ImportedTripBundle {
            val title = targetTitle?.trim()?.takeIf { it.isNotBlank() } ?: document.trip.title.trim()
            val dayIdMap = document.days.associate { it.id to "${targetTripId}_${it.id}" }
            val placeIdMap = document.places.associate { it.id to "${targetTripId}_${it.id}" }
            val activityIdMap = mutableMapOf<String, String>()

            val activities = mutableListOf<TripActivityEntity>()
            document.days.forEach { day ->
                val mappedDayId = dayIdMap.getValue(day.id)
                day.activities.forEachIndexed { index, activity ->
                    val mappedActivityId = "${targetTripId}_${activity.id}"
                    activityIdMap[activity.id] = mappedActivityId
                    activities += TripActivityEntity(
                        id = mappedActivityId,
                        tripId = targetTripId,
                        dayId = mappedDayId,
                        title = activity.title,
                        type = activity.type,
                        startTime = activity.startTime,
                        endTime = activity.endTime,
                        durationMinutes = activity.durationMinutes,
                        placeId = activity.placeId?.let { placeIdMap[it] },
                        notes = activity.notes,
                        flexibility = activity.flexibility,
                        condition = activity.condition,
                        transportMode = activity.transportMode,
                        status = "planned",
                        sortOrder = index * 10,
                    )
                    activity.alternatives.forEachIndexed { altIndex, alternative ->
                        val mappedAltId = "${targetTripId}_${alternative.id}"
                        activityIdMap[alternative.id] = mappedAltId
                        activities += TripActivityEntity(
                            id = mappedAltId,
                            tripId = targetTripId,
                            dayId = mappedDayId,
                            title = alternative.title,
                            type = "alternative",
                            durationMinutes = alternative.durationMinutes,
                            placeId = alternative.placeId?.let { placeIdMap[it] },
                            notes = alternative.notes,
                            flexibility = "conditional",
                            condition = alternative.condition,
                            transportMode = alternative.transportMode,
                            status = "planned",
                            sortOrder = index * 10 + altIndex + 1,
                        )
                    }
                }
            }

            return ImportedTripBundle(
                trip = TripEntity(
                    id = targetTripId,
                    title = title,
                    startDate = document.trip.startDate,
                    endDate = document.trip.endDate,
                    timezone = document.trip.timezone,
                    currency = document.trip.currency,
                    originalText = document.trip.originalText,
                ),
                days = document.days.mapIndexed { index, day ->
                    TripDayEntity(dayIdMap.getValue(day.id), targetTripId, day.date, day.title, index)
                },
                places = document.places.map { place ->
                    TripPlaceEntity(
                        placeIdMap.getValue(place.id), targetTripId, place.name, place.address, place.latitude, place.longitude,
                        place.googlePlaceId, place.kind, place.verification,
                    )
                },
                activities = activities,
                reminders = document.reminders.map { reminder ->
                    TripReminderEntity(
                        "${targetTripId}_${reminder.id}", targetTripId,
                        reminder.activityId?.let { activityIdMap[it] }, reminder.title,
                        reminder.note, reminder.localDateTime,
                    )
                },
            )
        }

        private fun parseDate(value: String, field: String): LocalDate =
            runCatching { LocalDate.parse(value) }.getOrElse { throw IllegalArgumentException("$field must use YYYY-MM-DD.") }

        private fun parseTime(value: String, field: String): LocalTime =
            runCatching { LocalTime.parse(value) }.getOrElse { throw IllegalArgumentException("$field must use 24-hour HH:MM.") }
    }
}
