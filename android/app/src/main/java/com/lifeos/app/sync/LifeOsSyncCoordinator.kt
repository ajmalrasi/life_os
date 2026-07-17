package com.lifeos.app.sync

import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.MoneyRepository
import com.lifeos.app.domain.repository.MoodRepository
import com.lifeos.app.domain.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

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
    private val settingsRepository: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun refreshOnAppOpen(): LifeOsSyncResult = mutex.withLock {
        val failures = mutableListOf<String>()
        runCatching { analyticsRepository.refresh(DASHBOARD_HISTORY_DAYS) }
            .onFailure { failures += "insights" }
        runCatching { moodRepository.refresh(PULSE_HISTORY_DAYS) }
            .onFailure { failures += "Pulse" }
        runCatching { moneyRepository.refresh(MONEY_HISTORY_DAYS, STATEMENT_HISTORY_MONTHS) }
            .onFailure { failures += "money" }

        finish(failures, "LifeOS refreshed")
    }

    suspend fun syncAll(): LifeOsSyncResult = mutex.withLock {
        val failures = mutableListOf<String>()

        // This checks incremental transaction alerts and statement summaries;
        // ordinary statement line items remain excluded by the backend.
        runCatching { moneyRepository.syncNow() }
            .onFailure { failures += "money and statements" }
        runCatching { moodRepository.refresh(PULSE_HISTORY_DAYS) }
            .onFailure { failures += "Pulse" }
        runCatching { analyticsRepository.recompute(RECOMPUTE_DAYS) }
            .onFailure { failures += "health and insights" }

        finish(failures, "Everything is up to date")
    }

    private suspend fun finish(failures: List<String>, successMessage: String): LifeOsSyncResult {
        if (failures.isEmpty()) {
            settingsRepository.setLastSyncDay(LocalDate.now())
            settingsRepository.setLastSyncAt(System.currentTimeMillis())
            return LifeOsSyncResult(successful = true, message = successMessage)
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
