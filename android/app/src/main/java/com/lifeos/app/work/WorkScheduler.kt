package com.lifeos.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import androidx.work.WorkManager
import com.lifeos.app.widget.SpendWidgetWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the background workers (Phase 1 #2 and #4).
 *
 * Both run roughly daily. We use unique periodic work so re-scheduling (e.g. on
 * app start, or when the reminder time changes) replaces the prior request
 * rather than stacking duplicates.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Called on app start: ensure the nightly sync is scheduled (idempotent). */
    fun ensureHealthSyncScheduled() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<NightlyRecomputeWorker>(Duration.ofHours(24))
            .setConstraints(constraints)
            .setInitialDelay(initialDelayTo(LocalTime.of(3, 0))) // ~3 AM
            .build()

        workManager.enqueueUniquePeriodicWork(
            NightlyRecomputeWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Keep the spend widget current even when the app has not been opened. */
    fun ensureSpendWidgetScheduled() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SpendWidgetWorker>(Duration.ofHours(6))
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            SpendWidgetWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Schedule (or reschedule) the evening Pulse reminder for [hour]. Uses
     * UPDATE so changing the time in Settings re-targets the next run.
     */
    fun schedulePulseReminder(hour: Int) {
        val request = PeriodicWorkRequestBuilder<PulseReminderWorker>(Duration.ofHours(24))
            .setInitialDelay(initialDelayTo(LocalTime.of(hour.coerceIn(0, 23), 0)))
            .build()

        workManager.enqueueUniquePeriodicWork(
            PulseReminderWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPulseReminder() {
        workManager.cancelUniqueWork(PulseReminderWorker.UNIQUE_NAME)
    }

    fun scheduleTripReminder(id: String, title: String, body: String, at: Instant) {
        val delay = Duration.between(Instant.now(), at).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<TripReminderWorker>()
            .setInitialDelay(delay)
            .setInputData(workDataOf(
                TripReminderWorker.KEY_TITLE to title,
                TripReminderWorker.KEY_BODY to body,
            ))
            .build()
        workManager.enqueueUniqueWork("trip-reminder-$id", ExistingWorkPolicy.REPLACE, request)
    }

    /** Delay from now until the next occurrence of [target] time-of-day. */
    private fun initialDelayTo(target: LocalTime): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(target)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
