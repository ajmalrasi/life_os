package com.lifeos.app.presentation.priority

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.lifeos.app.domain.model.PriorityItem

object PriorityCalendarLauncher {
    data class Draft(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val location: String,
        val description: String,
    )

    fun createDraft(item: PriorityItem): Draft? {
        val start = item.eventStart ?: return null
        return Draft(
            title = calendarTitle(item),
            startMillis = start.toEpochMilli(),
            endMillis = (item.eventEnd ?: start.plusSeconds(3600)).toEpochMilli(),
            // Long meeting URLs are never pasted into the event; the platform
            // name is enough, and Priority's Join button opens the link.
            location = item.location ?: meetingPlatform(item.meetingUrl).orEmpty(),
            description = item.summary,
        )
    }

    fun createInsertIntent(item: PriorityItem): Intent? {
        val draft = createDraft(item) ?: return null
        return Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, draft.endMillis)
            .putExtra(CalendarContract.Events.TITLE, draft.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, draft.description)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, draft.location)
    }

    fun launch(context: Context, item: PriorityItem): Boolean {
        val intent = createInsertIntent(item) ?: return false
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }

    /** "Microsoft Teams", "Google Meet", "Zoom", or null for no/unknown link. */
    fun meetingPlatform(url: String?): String? {
        val host = url?.lowercase() ?: return null
        return when {
            "teams.microsoft.com" in host || "teams.live.com" in host -> "Microsoft Teams"
            "meet.google.com" in host -> "Google Meet"
            "zoom.us" in host -> "Zoom"
            "webex.com" in host -> "Webex"
            else -> "Online meeting"
        }
    }

    private fun calendarTitle(item: PriorityItem): String = when {
        "interview" in item.labels -> listOfNotNull(item.company, "Interview", item.role).joinToString(" · ")
        item.company != null -> "${item.company} · ${item.subject}"
        else -> item.subject
    }
}
