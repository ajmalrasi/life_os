package com.lifeos.app.domain.model

import java.time.LocalDate

/** One-tap daily pulse — maps to backend mood fields so correlations still work. */
enum class DayPulse(val emoji: String, val label: String) {
    ROUGH("😞", "Rough"),
    MEH("😐", "Meh"),
    GOOD("🙂", "Good"),
    FLOW("🔥", "Flow"),
    ;

    fun toCheckin(note: String? = null): MoodCheckin = when (this) {
        ROUGH -> MoodCheckin(LocalDate.now(), mood = 2, energy = 2, focus = 2, stress = 4, note)
        MEH -> MoodCheckin(LocalDate.now(), mood = 3, energy = 3, focus = 3, stress = 3, note)
        GOOD -> MoodCheckin(LocalDate.now(), mood = 4, energy = 4, focus = 4, stress = 2, note)
        FLOW -> MoodCheckin(LocalDate.now(), mood = 5, energy = 5, focus = 5, stress = 1, note)
    }

    companion object {
        fun fromCheckin(checkin: MoodCheckin): DayPulse = when {
            checkin.mood >= 5 -> FLOW
            checkin.mood >= 4 -> GOOD
            checkin.mood <= 2 -> ROUGH
            else -> MEH
        }
    }
}
