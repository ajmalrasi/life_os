package com.lifeos.app.presentation.priority

import com.lifeos.app.domain.model.PriorityItem

/**
 * The five Priority categories the backend model assigns. Items classified
 * before the model-led pipeline carry the older, finer labels; those fold into
 * the nearest category so the list never shows a label without a filter.
 */
object PriorityCategories {
    val ordered = listOf("interview", "job", "event", "account", "personal")

    private val names = mapOf(
        "interview" to "Interview",
        "job" to "Jobs",
        "event" to "Events",
        "account" to "Account",
        "personal" to "Personal",
    )

    private val legacy = mapOf(
        "work" to "job",
        "recruiter" to "job",
        "appointment" to "event",
        "travel" to "event",
        "security" to "account",
        "deadline" to "account",
        "document" to "account",
    )

    fun of(item: PriorityItem): String? {
        val resolved = item.labels.mapNotNull { label -> label.takeIf { it in names } ?: legacy[label] }
        // Prefer the most specific category when legacy labels produced several.
        return ordered.firstOrNull { it in resolved }
    }

    fun name(category: String?): String = names[category] ?: "Other"
}
