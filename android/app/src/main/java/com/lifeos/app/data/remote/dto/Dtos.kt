package com.lifeos.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthRecordDto(
    @SerialName("source_id") val sourceId: String,
    val type: String,
    val value: Double,
    val unit: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

@Serializable
data class HealthBatchDto(val records: List<HealthRecordDto>)

@Serializable
data class IngestResultDto(val received: Int, val inserted: Int, val skipped: Int)

@Serializable
data class MoodDto(
    val day: String,
    val mood: Int,
    val energy: Int,
    val focus: Int,
    val stress: Int,
    val note: String? = null,
)

@Serializable
data class MoodOutDto(
    val id: Int,
    val day: String,
    val mood: Int,
    val energy: Int,
    val focus: Int,
    val stress: Int,
    val note: String? = null,
)

@Serializable
data class GithubDto(
    val day: String,
    val commits: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerialName("active_repos") val activeRepos: Int = 0,
    @SerialName("coding_minutes") val codingMinutes: Int = 0,
)

@Serializable
data class DailyScoreDto(
    val day: String,
    @SerialName("sleep_score") val sleepScore: Double? = null,
    @SerialName("recovery_score") val recoveryScore: Double? = null,
    @SerialName("execution_score") val executionScore: Double? = null,
    @SerialName("consistency_score") val consistencyScore: Double? = null,
    @SerialName("burnout_risk") val burnoutRisk: Double? = null,
    @SerialName("wellness_score") val wellnessScore: Double? = null,
    @SerialName("fitness_score") val fitnessScore: Double? = null,
)

@Serializable
data class DeviceTokenDto(
    val token: String,
    val platform: String = "android",
)

@Serializable
data class DeviceTokenResponseDto(
    val registered: Boolean,
    @SerialName("push_enabled") val pushEnabled: Boolean = false,
)

@Serializable
data class DailyStateDto(
    val day: String,
    val state: String,
    val confidence: Double,
    val rationale: String? = null,
)

@Serializable
data class InsightDto(
    val driver: String,
    val outcome: String,
    val correlation: Double,
    @SerialName("sample_size") val sampleSize: Int,
    @SerialName("lag_days") val lagDays: Int,
    val summary: String,
    val narrative: String? = null,
)

@Serializable
data class SynthesizedInsightDto(
    val title: String,
    val body: String,
    val theme: String? = null,
    val cites: List<String> = emptyList(),
)

@Serializable
data class RecomputeResultDto(
    @SerialName("days_scored") val daysScored: Int,
    @SerialName("states_classified") val statesClassified: Int,
    @SerialName("insights_found") val insightsFound: Int,
    @SerialName("execution_days_updated") val executionDaysUpdated: Int = 0,
    @SerialName("execution_repos_scanned") val executionReposScanned: Int = 0,
    @SerialName("execution_today_commits") val executionTodayCommits: Int? = null,
    @SerialName("execution_today_coding_minutes") val executionTodayCodingMinutes: Int? = null,
    @SerialName("execution_message") val executionMessage: String? = null,
)

@Serializable
data class ExecutionSyncResultDto(
    @SerialName("days_updated") val daysUpdated: Int,
    @SerialName("repos_scanned") val reposScanned: Int,
    val source: String,
    @SerialName("today_commits") val todayCommits: Int? = null,
    @SerialName("today_coding_minutes") val todayCodingMinutes: Int? = null,
    val message: String,
)

@Serializable
data class NotificationDto(val severity: String, val title: String, val detail: String)

@Serializable
data class ChatMessageDto(val role: String, val content: String)

@Serializable
data class ChatRequestDto(
    val message: String,
    val history: List<ChatMessageDto> = emptyList(),
    @SerialName("context_days") val contextDays: Int = 30,
)

@Serializable
data class CitationDto(val metric: String, val value: String)

@Serializable
data class ChatResponseDto(
    val answer: String,
    val citations: List<CitationDto> = emptyList(),
    val grounded: Boolean = true,
)

@Serializable
data class SuggestionsResponseDto(
    val questions: List<String> = emptyList(),
)

@Serializable
data class TransactionDto(
    val id: Int,
    @SerialName("txn_date") val txnDate: String,
    /** ISO-8601 instant the transaction happened; null for rows without a
     * known time (rendered date-only). */
    @SerialName("txn_at") val txnAt: String? = null,
    val amount: String,
    val currency: String = "INR",
    val direction: String,
    val counterparty: String? = null,
    val category: String? = null,
    @SerialName("spend_category") val spendCategory: String? = null,
    val account: String? = null,
    val source: String,
    val reference: String? = null,
)

@Serializable
data class ExpenseSyncResultDto(
    @SerialName("parsers_run") val parsersRun: Int = 0,
    @SerialName("emails_scanned") val emailsScanned: Int = 0,
    @SerialName("transactions_new") val transactionsNew: Int,
    @SerialName("transactions_seen") val transactionsSeen: Int,
    val message: String,
)

@Serializable
data class CardStatementDto(
    val id: Int,
    val bank: String,
    @SerialName("card_label") val cardLabel: String,
    @SerialName("card_last4") val cardLast4: String? = null,
    @SerialName("statement_date") val statementDate: String? = null,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("total_due") val totalDue: String? = null,
    @SerialName("min_due") val minDue: String? = null,
    @SerialName("total_spend") val totalSpend: String? = null,
)

@Serializable
data class StatementSyncResultDto(
    @SerialName("banks_run") val banksRun: Int = 0,
    @SerialName("emails_seen") val emailsSeen: Int = 0,
    @SerialName("statements_new") val statementsNew: Int,
    @SerialName("line_items_ingested") val lineItemsIngested: Int = 0,
    @SerialName("line_items_deduped") val lineItemsDeduped: Int = 0,
    val message: String,
)

@Serializable
data class CategorySpendDto(
    val category: String,
    val total: String,
    val count: Int,
)

@Serializable
data class MonthTrendDto(
    val month: String,
    @SerialName("total_debits") val totalDebits: String,
    @SerialName("total_credits") val totalCredits: String,
    @SerialName("txn_count") val txnCount: Int,
    @SerialName("by_category") val byCategory: List<CategorySpendDto> = emptyList(),
    @SerialName("by_account") val byAccount: List<CategorySpendDto> = emptyList(),
)

@Serializable
data class SpendTrendsDto(
    val months: List<MonthTrendDto> = emptyList(),
    @SerialName("aggregate_debits") val aggregateDebits: String,
    @SerialName("aggregate_by_category") val aggregateByCategory: List<CategorySpendDto> = emptyList(),
)

@Serializable
data class SpendAnalysisDto(
    @SerialName("total_due_all_cards") val totalDueAllCards: String,
    @SerialName("min_due_all_cards") val minDueAllCards: String,
    @SerialName("next_due_date") val nextDueDate: String? = null,
    val cards: List<CardStatementDto> = emptyList(),
    @SerialName("spend_by_category") val spendByCategory: List<CategorySpendDto> = emptyList(),
    @SerialName("top_merchants") val topMerchants: List<CategorySpendDto> = emptyList(),
)
