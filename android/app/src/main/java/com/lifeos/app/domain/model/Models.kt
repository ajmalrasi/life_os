package com.lifeos.app.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

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

/** The model-written read on today, grounded in facts the backend computed. */
data class TodayBrief(
    val state: String,
    val confidence: String,
    val headline: String,
    val summary: String,
    val suggestion: String,
    /** The model's judgement of recent workouts (duration and effort, not just count). */
    val workoutNote: String? = null,
    /** True when the model thinks the workouts need attention on Today. */
    val workoutAttention: Boolean = false,
    val generatedAt: Instant,
)

data class TodaySummary(
    val brief: TodayBrief?,
    /** ready, refreshing (data changed; a new brief is being written), or unavailable. */
    val briefStatus: String,
    val healthLastReceivedAt: Instant?,
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
    val action: String? = null,
    val severity: String? = null,
    val cites: List<String> = emptyList(),
)

data class AppNotification(
    val severity: String,
    val title: String,
    val detail: String,
)

/** One data source's sync health (bank mail, statements, Priority mail,
 * sleep/vitals, the local model), for the Settings "Data sources" panel. */
data class SourceStatus(
    val source: String,
    val lastSuccessAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastError: String?,
    val stale: Boolean,
)

/** A stored monthly budget limit (Stage 4b). Only the limit is cached from the
 * backend; spent-so-far/pace is computed on-device from cached transactions
 * (see [com.lifeos.app.presentation.money.budgetPace]) so it works offline. */
data class Budget(
    val category: String,
    val monthlyLimit: BigDecimal,
)

/** User-entered income facts (never derivable from parsed transaction emails).
 * Online-only — not cached in Room. */
data class MoneyProfile(
    val monthlyIncome: BigDecimal?,
    val savingsGoalPct: Int?,
)

/** A debit that repeats on a roughly weekly/monthly cadence at a roughly
 * stable amount (Stage 4c) — subscriptions, EMIs, memberships. Detection
 * only; never used to forecast card-cycle or budget totals. */
data class RecurringCharge(
    val counterparty: String,
    val cadence: String, // "weekly" | "monthly"
    val occurrences: Int,
    val medianAmount: BigDecimal,
    val lastAmount: BigDecimal,
    val lastDate: LocalDate,
    val nextExpectedDate: LocalDate,
    /** Positive = the last charge was more expensive than the typical one. */
    val priceChangePct: Double?,
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

data class Citation(val metric: String, val value: String, val label: String? = null)

sealed interface ChatStreamEvent {
    data class Delta(val text: String) : ChatStreamEvent
    data class Status(val text: String) : ChatStreamEvent
    data class Error(val text: String, val retryable: Boolean = true) : ChatStreamEvent
    data class Done(val citations: List<Citation>, val grounded: Boolean, val incomplete: Boolean = false) : ChatStreamEvent
}

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

data class RepaymentCandidate(
    val transaction: Transaction,
    val matchScore: Int,
    val reason: String,
)

data class Repayment(
    val id: Int,
    val debit: Transaction,
    val credit: Transaction,
)

data class Refund(
    val id: Int,
    val debit: Transaction,
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
    val paidAt: Instant?,
    val status: String,
    val detailsStatus: String,
    val detailsParserVersion: Int,
    val detailsParsedAt: Instant?,
)

/** One statement-only explanatory row. It is never part of spend totals. */
data class CardStatementItem(
    val id: Int,
    val sequence: Int,
    val txnDate: LocalDate,
    val txnTime: java.time.LocalTime?,
    val description: String,
    val amount: BigDecimal,
    val direction: String,
    val itemType: String,
)

data class StatementBreakdownItem(
    val key: String,
    val label: String,
    val total: BigDecimal,
    val count: Int,
)

data class CardStatementDetails(
    val statement: CardStatement,
    val items: List<CardStatementItem>,
    val breakdown: List<StatementBreakdownItem>,
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

/** One open credit-card billing cycle before its statement PDF exists. */
data class CurrentCardCycle(
    val key: String,
    val bank: String,
    val cardLabel: String,
    val cardLast4: String,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val currentSpend: BigDecimal,
    val refunds: BigDecimal,
    val netCycleAmount: BigDecimal,
    val transactionCount: Int,
    val lastTransactionDate: LocalDate?,
    val unconvertedTransactionCount: Int,
)

data class CurrentCardCycleDetails(
    val cycle: CurrentCardCycle,
    val transactions: List<Transaction>,
)
