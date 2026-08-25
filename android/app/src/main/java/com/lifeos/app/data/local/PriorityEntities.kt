package com.lifeos.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "priority_items_cache")
data class PriorityItemEntity(
    @PrimaryKey val id: Int,
    val subject: String,
    val sender: String,
    val company: String?,
    val role: String?,
    val importance: String,
    val labels: String,
    val state: String,
    val confidence: Double,
    val summary: String,
    val whyImportant: String,
    val evidence: String,
    val actionText: String?,
    val actionUrl: String?,
    val dueAt: String?,
    val eventStart: String?,
    val eventEnd: String?,
    val timezone: String?,
    val location: String?,
    val meetingUrl: String?,
    val calendarUid: String?,
    val hasCalendarInvite: Boolean,
    val gmailUrl: String,
    val preview: String,
    val receivedAt: String,
    val snoozedUntil: String?,
    val accountLabel: String,
    val accountAddress: String,
)
