package com.lifeos.app.data

import com.lifeos.app.data.local.PriorityItemEntity
import com.lifeos.app.data.remote.dto.PriorityDetailsDto
import com.lifeos.app.data.remote.dto.PriorityHistoryDto
import com.lifeos.app.data.remote.dto.PriorityItemDto
import com.lifeos.app.data.remote.dto.PrioritySyncResultDto
import com.lifeos.app.domain.model.PriorityDetails
import com.lifeos.app.domain.model.PriorityHistoryEntry
import com.lifeos.app.domain.model.PriorityItem
import com.lifeos.app.domain.model.PrioritySyncResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun parsePriorityInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
}

fun PriorityItemDto.toEntity() = PriorityItemEntity(
    id, subject, sender, company, role, importance, labels.joinToString("\u001F"), state,
    confidence, summary, whyImportant, evidence.joinToString("\u001F"), actionText,
    actionUrl, dueAt, eventStart, eventEnd, timezone, location, meetingUrl, calendarUid,
    hasCalendarInvite, gmailUrl, preview, receivedAt, snoozedUntil, accountLabel, accountAddress,
)

fun PriorityItemEntity.toDomain() = PriorityItem(
    id = id,
    subject = subject,
    sender = sender,
    company = company,
    role = role,
    importance = importance,
    labels = labels.split("\u001F").filter(String::isNotBlank),
    state = state,
    confidence = confidence,
    summary = summary,
    whyImportant = whyImportant,
    evidence = evidence.split("\u001F").filter(String::isNotBlank),
    actionText = actionText,
    actionUrl = actionUrl,
    dueAt = parsePriorityInstant(dueAt),
    eventStart = parsePriorityInstant(eventStart),
    eventEnd = parsePriorityInstant(eventEnd),
    timezone = timezone,
    location = location,
    meetingUrl = meetingUrl,
    calendarUid = calendarUid,
    hasCalendarInvite = hasCalendarInvite,
    gmailUrl = gmailUrl,
    preview = preview,
    receivedAt = parsePriorityInstant(receivedAt) ?: Instant.EPOCH,
    snoozedUntil = parsePriorityInstant(snoozedUntil),
    accountLabel = accountLabel,
    accountAddress = accountAddress,
)

fun PriorityItemDto.toDomain() = toEntity().toDomain()

fun PriorityHistoryDto.toDomain() = PriorityHistoryEntry(
    messageId = messageId,
    subject = subject,
    sender = sender,
    receivedAt = parsePriorityInstant(receivedAt),
    preview = preview,
)

fun PriorityDetailsDto.toDomain() = PriorityDetails(item.toDomain(), history.map { it.toDomain() })

fun PrioritySyncResultDto.toDomain() = PrioritySyncResult(
    successful = successful,
    itemsNew = itemsNew,
    itemsUpdated = itemsUpdated,
    pending = pending,
    message = message,
    accountErrors = accountErrors,
    newItems = newItems.map { it.toDomain() },
)
