package com.lifeos.app

import com.lifeos.app.domain.model.PriorityItem
import com.lifeos.app.presentation.priority.PriorityCalendarLauncher
import com.lifeos.app.presentation.priority.PriorityCategories
import com.lifeos.app.presentation.priority.PriorityViewModel
import com.lifeos.app.presentation.priority.calendarActionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PriorityFeatureTest {
    @Test
    fun filtersAreCategoryAndStateBasedWithoutMutatingTheFeed() {
        val interview = item(id = 1, labels = listOf("interview"), state = "scheduled")
        val personal = item(id = 2, labels = listOf("personal"), state = "needs_action")
        val all = listOf(interview, personal)

        assertEquals(listOf(interview), PriorityViewModel.filterItems(all, "interview"))
        assertEquals(listOf(personal), PriorityViewModel.filterItems(all, "action"))
        assertEquals(all, PriorityViewModel.filterItems(all, "all"))
    }

    @Test
    fun onlyNonEmptyFiltersAreOfferedAndLegacyLabelsFoldIntoCategories() {
        val legacyInterview = item(id = 1, labels = listOf("work", "interview"), state = "scheduled")
        val legacySecurity = item(id = 2, labels = listOf("security"), state = "informational")
        val chips = PriorityViewModel.filterChips(listOf(legacyInterview, legacySecurity))

        assertEquals(listOf("all", "scheduled", "interview", "account"), chips.map { it.key })
        assertEquals(listOf(2, 1, 1, 1), chips.map { it.count })
        assertEquals("Interview", PriorityCategories.name(PriorityCategories.of(legacyInterview)))
        assertEquals(listOf(legacySecurity), PriorityViewModel.filterItems(listOf(legacyInterview, legacySecurity), "account"))
    }

    @Test
    fun scheduledPlainEmailProducesCompleteCalendarDraft() {
        val start = Instant.parse("2026-08-28T09:30:00Z")
        val end = Instant.parse("2026-08-28T10:30:00Z")
        val item = item(
            id = 1,
            labels = listOf("work", "interview"),
            state = "scheduled",
            eventStart = start,
            eventEnd = end,
        )

        val draft = PriorityCalendarLauncher.createDraft(item)!!
        assertEquals("Acme · Interview · Senior Android Engineer", draft.title)
        assertEquals(start.toEpochMilli(), draft.startMillis)
        assertEquals(end.toEpochMilli(), draft.endMillis)
        assertEquals("https://meet.google.com/abc", draft.location)
        assertTrue(draft.description.contains("Meeting: https://meet.google.com/abc"))
        assertTrue(draft.description.contains("Source: https://mail.google.com/example"))
        assertEquals("Add to calendar", calendarActionLabel(item))
    }

    @Test
    fun structuredInviteAndSchedulingRequestUseContextualActions() {
        val invite = item(
            id = 1,
            labels = listOf("interview"),
            state = "scheduled",
            eventStart = Instant.parse("2026-08-28T09:30:00Z"),
            hasCalendarInvite = true,
        )
        val scheduling = item(
            id = 2,
            labels = listOf("interview"),
            state = "needs_action",
            actionUrl = "https://calendly.com/acme/interview",
        )
        val ordinary = item(id = 3, labels = listOf("personal"), state = "informational")

        assertEquals("Open calendar invite", calendarActionLabel(invite))
        assertEquals("Choose a time", calendarActionLabel(scheduling))
        assertNull(calendarActionLabel(ordinary))
    }

    private fun item(
        id: Int,
        labels: List<String>,
        state: String,
        eventStart: Instant? = null,
        eventEnd: Instant? = null,
        hasCalendarInvite: Boolean = false,
        actionUrl: String? = null,
    ) = PriorityItem(
        id = id,
        subject = "Technical interview confirmed",
        sender = "Nadia <nadia@acme.example>",
        company = "Acme",
        role = "Senior Android Engineer",
        importance = "high",
        labels = labels,
        state = state,
        confidence = 0.95,
        summary = "Interview with the Android team.",
        whyImportant = "Confirmed interview schedule.",
        evidence = listOf("interview_language"),
        actionText = null,
        actionUrl = actionUrl,
        dueAt = null,
        eventStart = eventStart,
        eventEnd = eventEnd,
        timezone = "Asia/Kolkata",
        location = null,
        meetingUrl = "https://meet.google.com/abc",
        calendarUid = null,
        hasCalendarInvite = hasCalendarInvite,
        gmailUrl = "https://mail.google.com/example",
        preview = "Interview confirmed.",
        receivedAt = Instant.parse("2026-08-25T04:30:00Z"),
        snoozedUntil = null,
        accountLabel = "Career inbox",
        accountAddress = "career@example.com",
    )
}
