package com.lifeos.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PriorityItemDto(
    val id: Int,
    val subject: String,
    val sender: String,
    val company: String? = null,
    val role: String? = null,
    val importance: String,
    val labels: List<String> = emptyList(),
    val state: String,
    val confidence: Double,
    val summary: String,
    @SerialName("why_important") val whyImportant: String,
    val evidence: List<String> = emptyList(),
    @SerialName("action_text") val actionText: String? = null,
    @SerialName("action_url") val actionUrl: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("event_start") val eventStart: String? = null,
    @SerialName("event_end") val eventEnd: String? = null,
    val timezone: String? = null,
    val location: String? = null,
    @SerialName("meeting_url") val meetingUrl: String? = null,
    @SerialName("calendar_uid") val calendarUid: String? = null,
    @SerialName("has_calendar_invite") val hasCalendarInvite: Boolean = false,
    @SerialName("gmail_url") val gmailUrl: String,
    val preview: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
    @SerialName("account_label") val accountLabel: String,
    @SerialName("account_address") val accountAddress: String,
)

@Serializable
data class PriorityHistoryDto(
    @SerialName("message_id") val messageId: String,
    val subject: String,
    val sender: String,
    @SerialName("received_at") val receivedAt: String? = null,
    val preview: String,
    val classification: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PriorityDetailsDto(
    val item: PriorityItemDto,
    val history: List<PriorityHistoryDto> = emptyList(),
)

@Serializable
data class PriorityUpdateDto(
    val state: String? = null,
    val labels: List<String>? = null,
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
)

@Serializable
data class PriorityFeedbackDto(
    val kind: String,
    val value: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PrioritySyncResultDto(
    val successful: Boolean,
    @SerialName("accounts_run") val accountsRun: Int = 0,
    @SerialName("emails_found") val emailsFound: Int = 0,
    @SerialName("emails_processed") val emailsProcessed: Int = 0,
    @SerialName("items_new") val itemsNew: Int = 0,
    @SerialName("items_updated") val itemsUpdated: Int = 0,
    @SerialName("items_review") val itemsReview: Int = 0,
    val pending: Int = 0,
    val message: String,
    @SerialName("account_errors") val accountErrors: List<String> = emptyList(),
    @SerialName("new_items") val newItems: List<PriorityItemDto> = emptyList(),
)
