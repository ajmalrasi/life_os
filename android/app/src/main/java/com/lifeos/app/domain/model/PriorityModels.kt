package com.lifeos.app.domain.model

import java.time.Instant

data class PriorityItem(
    val id: Int,
    val subject: String,
    val sender: String,
    val company: String?,
    val role: String?,
    val importance: String,
    val labels: List<String>,
    val state: String,
    val confidence: Double,
    val summary: String,
    val whyImportant: String,
    val evidence: List<String>,
    val actionText: String?,
    val actionUrl: String?,
    val dueAt: Instant?,
    val eventStart: Instant?,
    val eventEnd: Instant?,
    val timezone: String?,
    val location: String?,
    val meetingUrl: String?,
    val calendarUid: String?,
    val hasCalendarInvite: Boolean,
    val gmailUrl: String,
    val preview: String,
    val receivedAt: Instant,
    val snoozedUntil: Instant?,
    val accountLabel: String,
    val accountAddress: String,
)

data class PriorityHistoryEntry(
    val messageId: String,
    val subject: String,
    val sender: String,
    val receivedAt: Instant?,
    val preview: String,
)

data class PriorityDetails(
    val item: PriorityItem,
    val history: List<PriorityHistoryEntry>,
)

data class PrioritySyncResult(
    val successful: Boolean,
    val itemsNew: Int,
    val itemsUpdated: Int,
    val pending: Int,
    val message: String,
    val accountErrors: List<String>,
    val newItems: List<PriorityItem>,
)
