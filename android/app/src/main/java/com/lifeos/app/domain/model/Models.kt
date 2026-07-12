package com.lifeos.app.domain.model

import java.math.BigDecimal
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
    /** LLM-written retelling of the same facts; falls back to [summary] when null. */
    val narrative: String? = null,
)

/** An LLM-synthesized, cross-domain insight that connects several discovered
 * facts. Grounded: [cites] holds the evidence fact ids it was built from. */
data class SynthesizedInsight(
    val title: String,
    val body: String,
    val theme: String? = null,
    val cites: List<String> = emptyList(),
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

/** A single financial transaction, from either a live email alert (UPI) or a
 * parsed statement PDF (cc_statement / bank_account). */
data class Transaction(
    val id: Int,
    val txnDate: LocalDate,
    /** When the transaction happened; null when no time is known (date-only). */
    val txnAt: Instant?,
    val amount: BigDecimal,
    val currency: String,
    val direction: String, // "debit" | "credit"
    val counterparty: String?,
    val category: String?,
    /** Spend-analysis category (labels + keyword rules); null when the row is
     * excluded from spend (transfers, reversals, lent-and-returned). */
    val spendCategory: String?,
    val account: String?,
    val source: String,
    val reference: String?,
)

/** One monthly credit-card statement summary. */
data class CardStatement(
    val id: Int,
    val bank: String,
    val cardLabel: String,
    val cardLast4: String?,
    val statementDate: LocalDate?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val dueDate: LocalDate?,
    val totalDue: BigDecimal?,
    val minDue: BigDecimal?,
    val totalSpend: BigDecimal?,
)

data class CategorySpend(
    val category: String,
    val total: BigDecimal,
    val count: Int,
)

/** One calendar month of combined spend (UPI feed + statement line items). */
data class MonthSpend(
    val month: String, // "2026-06"
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val txnCount: Int,
    val byCategory: List<CategorySpend>,
)

data class SpendTrends(
    val months: List<MonthSpend>,
    val aggregateDebits: BigDecimal,
    val aggregateByCategory: List<CategorySpend>,
)

/** Month-at-a-glance across all cards + the live UPI feed. */
data class SpendAnalysis(
    val totalDueAllCards: BigDecimal,
    val minDueAllCards: BigDecimal,
    val nextDueDate: LocalDate?,
    val cards: List<CardStatement>,
    val spendByCategory: List<CategorySpend>,
    val topMerchants: List<CategorySpend>,
)
