package com.lifeos.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


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
data class SourceStatusDto(
    val source: String,
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("last_attempt_at") val lastAttemptAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val stale: Boolean,
)

@Serializable
data class SyncStatusDto(val sources: List<SourceStatusDto>)

@Serializable
data class BudgetPaceDto(
    val category: String,
    @SerialName("monthly_limit") val monthlyLimit: String,
    @SerialName("spent_so_far") val spentSoFar: String,
    @SerialName("projected_month_total") val projectedMonthTotal: String,
    @SerialName("days_elapsed") val daysElapsed: Int,
    @SerialName("days_in_month") val daysInMonth: Int,
    @SerialName("on_pace_to_exceed") val onPaceToExceed: Boolean,
    @SerialName("projected_exceed_day") val projectedExceedDay: Int? = null,
)

@Serializable
data class BudgetInDto(@SerialName("monthly_limit") val monthlyLimit: String)

@Serializable
data class ChatMessageDto(val role: String, val content: String)

@Serializable
data class ChatRequestDto(
    val message: String,
    val history: List<ChatMessageDto> = emptyList(),
    @SerialName("context_days") val contextDays: Int = 30,
)

@Serializable
data class CitationDto(
    val metric: String, val value: String, val label: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("retrieved_at") val retrievedAt: String? = null,
)

@Serializable
data class ChatResponseDto(
    val answer: String,
    val citations: List<CitationDto> = emptyList(),
    val grounded: Boolean = true,
    val error: String? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class ChatStreamEventDto(
    val type: String,
    val text: String? = null,
    val citations: List<CitationDto> = emptyList(),
    val grounded: Boolean = false,
    val incomplete: Boolean = false,
    val retryable: Boolean = true,
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
data class TransactionCategoryUpdateDto(val category: String?)

@Serializable
data class RepaymentCandidateDto(
    val transaction: TransactionDto,
    @SerialName("match_score") val matchScore: Int,
    val reason: String,
)

@Serializable
data class RepaymentCreateDto(
    @SerialName("debit_transaction_id") val debitTransactionId: Int,
    @SerialName("credit_transaction_id") val creditTransactionId: Int,
)

@Serializable
data class RepaymentDto(
    val id: Int,
    val debit: TransactionDto,
    val credit: TransactionDto,
)

@Serializable
data class RefundCreateDto(
    @SerialName("debit_transaction_id") val debitTransactionId: Int,
)

@Serializable
data class RefundDto(
    val id: Int,
    val debit: TransactionDto,
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
    @SerialName("paid_at") val paidAt: String? = null,
    val status: String = "upcoming",
    @SerialName("details_status") val detailsStatus: String = "pending",
    @SerialName("details_parser_version") val detailsParserVersion: Int = 0,
    @SerialName("details_parsed_at") val detailsParsedAt: String? = null,
)

@Serializable
data class CardStatementItemDto(
    val id: Int,
    val sequence: Int,
    @SerialName("txn_date") val txnDate: String,
    @SerialName("txn_time") val txnTime: String? = null,
    val description: String,
    val amount: String,
    val direction: String,
    @SerialName("item_type") val itemType: String,
)

@Serializable
data class StatementBreakdownItemDto(
    val key: String,
    val label: String,
    val total: String,
    val count: Int,
)

@Serializable
data class CardStatementDetailsDto(
    val statement: CardStatementDto,
    val items: List<CardStatementItemDto> = emptyList(),
    val breakdown: List<StatementBreakdownItemDto> = emptyList(),
)

@Serializable
data class StatementPaidUpdateDto(val paid: Boolean)

@Serializable
data class StatementSyncResultDto(
    @SerialName("banks_run") val banksRun: Int = 0,
    @SerialName("emails_seen") val emailsSeen: Int = 0,
    @SerialName("statements_new") val statementsNew: Int,
    @SerialName("line_items_ingested") val lineItemsIngested: Int = 0,
    @SerialName("line_items_stored") val lineItemsStored: Int = 0,
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

@Serializable
data class CurrentCardCycleDto(
    val bank: String,
    @SerialName("card_label") val cardLabel: String,
    @SerialName("card_last4") val cardLast4: String,
    @SerialName("cycle_start") val cycleStart: String,
    @SerialName("cycle_end") val cycleEnd: String,
    @SerialName("current_spend") val currentSpend: String,
    val refunds: String,
    @SerialName("net_cycle_amount") val netCycleAmount: String,
    @SerialName("transaction_count") val transactionCount: Int,
    @SerialName("last_transaction_date") val lastTransactionDate: String? = null,
    @SerialName("unconverted_transaction_count") val unconvertedTransactionCount: Int = 0,
)

@Serializable
data class CurrentCardCyclesDto(
    @SerialName("as_of") val asOf: String,
    @SerialName("current_spend_total") val currentSpendTotal: String,
    @SerialName("refund_total") val refundTotal: String,
    @SerialName("net_cycle_total") val netCycleTotal: String,
    val cycles: List<CurrentCardCycleDto> = emptyList(),
)

@Serializable
data class CurrentCardCycleDetailsDto(
    val cycle: CurrentCardCycleDto,
    val transactions: List<TransactionDto> = emptyList(),
)
