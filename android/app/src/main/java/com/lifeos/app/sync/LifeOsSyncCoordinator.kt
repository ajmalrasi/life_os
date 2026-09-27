package com.lifeos.app.sync

import com.lifeos.app.places.data.MovementUploader
import com.lifeos.app.domain.model.TodaySummary
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.MoneyRepository
import com.lifeos.app.domain.repository.MoodRepository
import com.lifeos.app.domain.repository.PriorityRepository
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.trips.data.TripRepository
import com.lifeos.app.widget.SpendWidgetUpdater
import com.lifeos.app.work.WorkScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 * Settings action (Sync everything) runs every source LifeOS can pull and
 * waits for all of it, including the work the backend would otherwise finish
 * in the background: bank mail and card statements, Priority mail, the GitHub
 * scan, trip backups, the recompute (scores, correlations, Insights,
 * notifications) and Today's brief. Health data is the one source it cannot
 * pull (LifeOS never contacts the iPhone), so the result says when it last
 * arrived instead.
 */
@Singleton
class LifeOsSyncCoordinator @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val moneyRepository: MoneyRepository,
    private val moodRepository: MoodRepository,
    private val priorityRepository: PriorityRepository,
    private val settingsRepository: SettingsRepository,
    private val spendWidgetUpdater: SpendWidgetUpdater,
    private val tripRepository: TripRepository,
    private val workScheduler: WorkScheduler,
    private val movementUploader: MovementUploader,
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

    /** [onStep] names each step as it starts (for the progress notification). */
    suspend fun syncAll(onStep: suspend (String) -> Unit = {}): LifeOsSyncResult = mutex.withLock {
        val failures = mutableListOf<String>()
        var unauthorized = false
        fun <T> Result<T>.track(label: String) = onFailure {
            failures += label
            if (it.isUnauthorized()) unauthorized = true
        }

        // This checks incremental transaction alerts and statement summaries;
        // ordinary statement line items remain excluded by the backend.
        onStep("Checking bank mail and card statements")
        runCatching { moneyRepository.syncNow() }.track("money and statements")
        onStep("Checking Priority mail")
        runCatching { priorityRepository.syncNow() }.track("Priority mail")
        runCatching { spendWidgetUpdater.updateAll() }
        runCatching { moodRepository.refresh(PULSE_HISTORY_DAYS) }.track("Pulse")
        onStep("Backing up trips")
        // Every trip, regardless of the auto-backup setting: this is the
        // explicit "everything" action. Before the recompute so Insights see it.
        runCatching {
            tripRepository.observeTrips().first().forEach { tripRepository.backup(it.id) }
        }.track("trip backup")
        if (settingsRepository.placesEnabled.first()) {
            onStep("Sending places")
            runCatching { movementUploader.uploadRecent() }.track("places")
        }
        // Scan GitHub here and wait for it, so the recompute below scores
        // today's coding instead of the backend scanning after we return.
        onStep("Scanning GitHub activity")
        runCatching { analyticsRepository.syncExecution(EXECUTION_DAYS) }.track("GitHub activity")
        onStep("Recomputing scores and insights")
        runCatching { analyticsRepository.recompute(RECOMPUTE_DAYS, scanExecution = false) }
            .track("health and insights")
        onStep("Writing Today's brief")
        val today = waitForTodayBrief()
        if (today == null) failures += "Today's brief"
        refreshCardDueReminders()

        finish(failures, unauthorized, "Everything is up to date. ${healthLine(today?.healthLastReceivedAt)}")
    }

    /** The recompute starts a rewrite of Today's brief in the background; wait
     * for it so "up to date" is true. Null if it never settled (model busy or
     * down; the previous brief stays). */
    private suspend fun waitForTodayBrief(): TodaySummary? {
        repeat(BRIEF_POLLS) { attempt ->
            if (attempt > 0) delay(BRIEF_POLL_MS)
            val summary = runCatching { analyticsRepository.today() }.getOrNull() ?: return null
            if (summary.briefStatus != "refreshing") {
                // The brief also writes the day's state row.
                runCatching { analyticsRepository.refresh(RECOMPUTE_DAYS) }
                return summary
            }
        }
        return null
    }

    private fun healthLine(receivedAt: Instant?): String {
        if (receivedAt == null) return "Health data arrives only when the iPhone pushes it."
        val local = receivedAt.atZone(ZoneId.systemDefault())
        val pattern = if (local.toLocalDate() == LocalDate.now()) "HH:mm" else "d MMM, HH:mm"
        return "Health data last arrived from the iPhone ${local.format(DateTimeFormatter.ofPattern(pattern))}."
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
        // Matches the backend's own background scan window.
        const val EXECUTION_DAYS = 7
        // Up to two model calls of 45 s each (a rejected reply gets one retry).
        const val BRIEF_POLLS = 35
        const val BRIEF_POLL_MS = 3_000L
    }
}
