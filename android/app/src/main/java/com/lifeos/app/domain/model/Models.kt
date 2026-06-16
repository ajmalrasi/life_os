package com.lifeos.app.domain.model

import java.time.Instant
import java.time.LocalDate

/** A single Health Connect sample, normalized for upload. */
data class HealthSample(
    val sourceId: String,
    val type: HealthMetricType,
    val value: Double,
    val unit: String,
    val start: Instant,
    val end: Instant,
)

enum class HealthMetricType(val wire: String) {
    SLEEP_DURATION("sleep_duration"),
    SLEEP_DEEP("sleep_deep"),
    SLEEP_REM("sleep_rem"),
    SLEEP_LIGHT("sleep_light"),
    SLEEP_AWAKE("sleep_awake"),
    HEART_RATE("heart_rate"),
    RESTING_HEART_RATE("resting_heart_rate"),
    HRV("hrv"),
    BLOOD_OXYGEN("blood_oxygen"),
    WEIGHT("weight"),
    STEPS("steps"),
    WORKOUT("workout"),
    CALORIES("calories"),
    ACTIVE_CALORIES("active_calories"),
    BASAL_METABOLIC_RATE("basal_metabolic_rate"),
    WALKING_DISTANCE("walking_distance"),
    ELEVATION_GAINED("elevation_gained"),
    FLOORS_CLIMBED("floors_climbed"),
    VO2_MAX("vo2_max"),
    RESPIRATORY_RATE("respiratory_rate"),
    BLOOD_PRESSURE_SYSTOLIC("blood_pressure_systolic"),
    BLOOD_PRESSURE_DIASTOLIC("blood_pressure_diastolic"),
    BLOOD_GLUCOSE("blood_glucose"),
    BODY_TEMPERATURE("body_temperature"),
    BASAL_BODY_TEMPERATURE("basal_body_temperature"),
    HEIGHT("height"),
    BODY_FAT("body_fat"),
    LEAN_BODY_MASS("lean_body_mass"),
    BONE_MASS("bone_mass"),
    BODY_WATER_MASS("body_water_mass"),
    HYDRATION("hydration"),
}

data class MoodCheckin(
    val day: LocalDate,
    val mood: Int,
    val energy: Int,
    val focus: Int,
    val stress: Int,
    val note: String? = null,
)

data class GithubActivity(
    val day: LocalDate,
    val commits: Int,
    val additions: Int = 0,
    val deletions: Int = 0,
    val activeRepos: Int = 0,
    val codingMinutes: Int = 0,
)

data class DailyScore(
    val day: LocalDate,
    val sleepScore: Double?,
    val recoveryScore: Double?,
    val executionScore: Double?,
    val consistencyScore: Double?,
    val burnoutRisk: Double?,
    val wellnessScore: Double? = null,
    val fitnessScore: Double? = null,
)

data class DailyState(
    val day: LocalDate,
    val state: String,
    val confidence: Double,
    val rationale: String?,
)

data class Insight(
    val driver: String,
    val outcome: String,
    val correlation: Double,
    val sampleSize: Int,
    val lagDays: Int,
    val summary: String,
)

data class AppNotification(
    val severity: String,
    val title: String,
    val detail: String,
)

data class ChatTurn(
    val role: String, // "user" | "assistant"
    val content: String,
)

data class ChatAnswer(
    val answer: String,
    val citations: List<Citation>,
    val grounded: Boolean,
)

data class Citation(val metric: String, val value: String)

data class ExecutionSyncReport(
    val daysUpdated: Int,
    val reposScanned: Int,
    val source: String,
    val todayCommits: Int?,
    val todayCodingMinutes: Int?,
    val message: String,
)
