package com.lifeos.app.domain.repository

import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.CardStatementDetails
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.CurrentCardCycleDetails
import com.lifeos.app.domain.model.ChatAnswer
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.ChatStreamEvent
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.GithubActivity
import com.lifeos.app.domain.model.HealthSample
import com.lifeos.app.domain.model.ExecutionSyncReport
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.MoodCheckin
import com.lifeos.app.domain.model.Repayment
import com.lifeos.app.domain.model.RepaymentCandidate
import com.lifeos.app.domain.model.Refund
import com.lifeos.app.domain.model.SpendAnalysis
import com.lifeos.app.domain.model.SpendTrends
import com.lifeos.app.domain.model.SynthesizedInsight
import com.lifeos.app.domain.model.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Repository interfaces (domain layer). Implementations live in data/ and are
 * the only place that knows about Retrofit / Room / Health Connect.
 *
 * Offline-first contract: reads return cached data via [Flow]; an explicit
 * [refresh] reconciles with the backend and updates the cache.
 */
interface HealthRepository {
    /** Read recent samples from Health Connect (does not upload). */
    suspend fun readRecentSamples(days: Int): List<HealthSample>

    /** Upload samples to the backend; returns number accepted. */
    suspend fun sync(samples: List<HealthSample>): Int
}

interface MoodRepository {
    fun observe(days: Int): Flow<List<MoodCheckin>>
    suspend fun submit(checkin: MoodCheckin)
    suspend fun refresh(days: Int)
}

interface GithubRepository {
    suspend fun submit(activity: GithubActivity)
    suspend fun refresh(days: Int)
}

interface AnalyticsRepository {
    fun observeScores(days: Int): Flow<List<DailyScore>>
    fun observeStates(days: Int): Flow<List<DailyState>>
    fun observeInsights(): Flow<List<Insight>>
    /** LLM-synthesized, cross-domain insights (grounded in cited evidence). */
    fun observeSynthesizedInsights(): Flow<List<SynthesizedInsight>>
    suspend fun refresh(days: Int)
    /** Trigger a backend recompute (nightly analysis on demand). */
    suspend fun recompute(days: Int): ExecutionSyncReport
    suspend fun notifications(): List<AppNotification>
}

interface ChatRepository {
    suspend fun ask(message: String, history: List<ChatTurn>, contextDays: Int): ChatAnswer

    /** Incremental answer text followed by grounded/citation metadata. */
    fun stream(message: String, history: List<ChatTurn>, contextDays: Int): Flow<ChatStreamEvent>

    /** Data-grounded starter questions for the Ask screen. Never throws; returns
     *  sensible defaults if the backend is unreachable. */
    suspend fun suggestions(contextDays: Int): List<String>
}

interface MoneyRepository {
    /** Cached transactions in the trailing window (live alerts + selected statement EMI costs). */
    fun observeTransactions(days: Int): Flow<List<Transaction>>

    /** Cached per-card statement summaries. */
    fun observeStatements(months: Int): Flow<List<CardStatement>>

    /** Cached server-calculated open billing cycles for every known credit card. */
    fun observeCurrentCardCycles(): Flow<List<CurrentCardCycle>>

    /** Exact transactions contributing to one active card cycle. */
    suspend fun currentCardCycleDetails(bank: String, cardLast4: String): CurrentCardCycleDetails

    /** Pull fresh transactions + statement summaries from the backend into the cache. */
    suspend fun refresh(transactionDays: Int = 90, statementMonths: Int = 7)

    /** Month-at-a-glance: dues across every card + category/merchant breakdown.
     * Computed server-side on every call, not cached, always current. */
    suspend fun analysis(days: Int = 45): SpendAnalysis

    /** Month-by-month spend trend. Computed server-side on every call. */
    suspend fun trends(months: Int = 6): SpendTrends

    /** Manually trigger the backend's incremental sync (pull-to-refresh); the
     * Jetson cron already runs this periodically, so this is a user-initiated
     * "check now" rather than the primary ingestion path. */
    suspend fun syncNow()

    /** Later credits with matching amount/currency, ranked as possible returns
     * of the selected debit. Suggestions never mutate data. */
    suspend fun repaymentCandidates(debitId: Int): List<RepaymentCandidate>

    /** Reversibly exclude a lent debit and its returned credit from totals. */
    suspend fun markAsRepaid(debitId: Int, creditId: Int): Repayment

    /** Restore both transactions to their previous classifications. */
    suspend fun undoRepayment(repaymentId: Int): Repayment

    /** Reversibly exclude a merchant debit when its refund credit was not ingested. */
    suspend fun markAsRefunded(debitId: Int): Refund

    /** Restore a manually refunded debit to its previous classification. */
    suspend fun undoRefund(refundId: Int): Refund

    /** Persist a category for one transaction; null restores automatic categorization. */
    suspend fun updateTransactionCategory(transactionId: Int, category: String?): Transaction

    /** Mark an individual card statement paid early, or restore it to upcoming dues. */
    suspend fun updateStatementPaid(statementId: Int, paid: Boolean): CardStatement

    /** Full statement-only activity for explaining a bill; never included in spend. */
    suspend fun statementDetails(statementId: Int): CardStatementDetails
}

interface SettingsRepository {
    val apiBaseUrl: Flow<String>
    suspend fun setApiBaseUrl(url: String)
    val lastSyncDay: Flow<LocalDate?>
    suspend fun setLastSyncDay(day: LocalDate)

    /** Wall-clock time (epoch millis) of the last successful sync, for display. */
    val lastSyncAt: Flow<Long?>
    suspend fun setLastSyncAt(epochMillis: Long)

    /** Evening Pulse reminder configuration. */
    val pulseReminderEnabled: Flow<Boolean>
    suspend fun setPulseReminderEnabled(enabled: Boolean)
    val pulseReminderHour: Flow<Int>
    suspend fun setPulseReminderHour(hour: Int)

    /** Navigation and startup screen preferences. */
    val reopenLastTab: Flow<Boolean>
    suspend fun setReopenLastTab(enabled: Boolean)
    val lastUsedTab: Flow<String?>
    suspend fun setLastUsedTab(route: String)
    suspend fun startupDestination(): String
}
