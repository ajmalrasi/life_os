package com.lifeos.app.sync

import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.MoneyRepository
import com.lifeos.app.domain.repository.MoodRepository
import com.lifeos.app.domain.repository.PriorityRepository
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.widget.SpendWidgetUpdater
import com.lifeos.app.work.WorkScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** True when the backend rejected the request over the bearer token (wrong,
 * missing, or the backend has one set that this device doesn't). One bad
 * token breaks every call, so it deserves one specific, actionable message
 * instead of getting buried in the generic per-area failure list below. */
private fun Throwable.isUnauthorized(): Boolean = this is HttpException && code() == 401

data class LifeOsSyncResult(
    val successful: Boolean,
    val message: String,
)

/**
 * One owner for user-visible sync behavior.
 *
 * App launch only refreshes the phone's caches from the Jetson, which is fast
 * and cannot occupy the local LLM while the user opens Ask. The explicit
 * Settings action additionally checks bank email/card statements and asks the
 * backend to recompute health, execution, correlations, and notifications.
 */
@Singleton
class LifeOsSyncCoordinator @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val moneyRepository: MoneyRepository,
    private val moodRepository: MoodRepository,
    private val priorityRepository: PriorityRepository,
    private val settingsRepository: SettingsRepository,
    private val spendWidgetUpdater: SpendWidgetUpdater,
    private val workScheduler: WorkScheduler,
) {
    private val mutex = Mutex()

    suspend fun refreshOnAppOpen(): LifeOsSyncResult = mutex.withLock {
        val failures = mutableListOf<String>()
        var unauthorized = false
        fun <T> Result<T>.track(label: String) = onFailure {
            failures += label
            if (it.isUnauthorized()) unauthorized = true
        }

        runCatching { analyticsRepository.refresh(DASHBOARD_HISTORY_DAYS) }.track("insights")
        runCatching { moodRepository.refresh(PULSE_HISTORY_DAYS) }.track("Pulse")
        runCatching { moneyRepository.refresh(MONEY_HISTORY_DAYS, STATEMENT_HISTORY_MONTHS) }
            .track("money")
        runCatching { priorityRepository.refresh() }.track("Priority")
        runCatching { spendWidgetUpdater.updateAll() }
        refreshCardDueReminders()

        finish(failures, unauthorized, "LifeOS refreshed")
    }

    suspend fun syncAll(): LifeOsSyncResult = mutex.withLock {
        val failures = mutableListOf<String>()
        var unauthorized = false
        fun <T> Result<T>.track(label: String) = onFailure {
            failures += label
            if (it.isUnauthorized()) unauthorized = true
        }

        // This checks incremental transaction alerts and statement summaries;
        // ordinary statement line items remain excluded by the backend.
        runCatching { moneyRepository.syncNow() }.track("money and statements")
        runCatching { priorityRepository.syncNow() }.track("Priority mail")
        runCatching { spendWidgetUpdater.updateAll() }
        runCatching { moodRepository.refresh(PULSE_HISTORY_DAYS) }.track("Pulse")
        runCatching { analyticsRepository.recompute(RECOMPUTE_DAYS) }.track("health and insights")
        refreshCardDueReminders()

        finish(failures, unauthorized, "Everything is up to date")
    }

    /** Reschedule (or cancel) card-due reminders from the freshly-refreshed
     * statement cache. Best-effort: a scheduling failure is never a sync
     * failure — the statements themselves already refreshed successfully. */
    private suspend fun refreshCardDueReminders() {
        runCatching {
            if (settingsRepository.cardDueRemindersEnabled.first()) {
                val statements = moneyRepository.observeStatements(STATEMENT_HISTORY_MONTHS).first()
                workScheduler.scheduleCardDueReminders(statements)
            } else {
                workScheduler.cancelAllCardDueReminders()
            }
        }
    }

    private suspend fun finish(
        failures: List<String>,
        unauthorized: Boolean,
        successMessage: String,
    ): LifeOsSyncResult {
        if (failures.isEmpty()) {
            settingsRepository.setLastSyncDay(LocalDate.now())
            settingsRepository.setLastSyncAt(System.currentTimeMillis())
            return LifeOsSyncResult(successful = true, message = successMessage)
        }
        if (unauthorized) {
            // Every area failed the same way; naming them individually would
            // bury the one fix (the token) under a list that looks like N
            // unrelated problems.
            return LifeOsSyncResult(
                successful = false,
                message = "Unauthorized — check the API token in Settings.",
            )
        }
        return LifeOsSyncResult(
            successful = false,
            message = "Couldn't refresh ${failures.joinToString()}.",
        )
    }

    private companion object {
        const val DASHBOARD_HISTORY_DAYS = 365
        const val MONEY_HISTORY_DAYS = 400
        const val STATEMENT_HISTORY_MONTHS = 7
        const val PULSE_HISTORY_DAYS = 30
        const val RECOMPUTE_DAYS = 60
    }
}
