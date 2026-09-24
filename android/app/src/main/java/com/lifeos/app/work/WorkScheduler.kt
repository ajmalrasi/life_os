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
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.widget.SpendWidgetWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
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

    /** Best-effort backup of one trip, 30s after a user edit so rapid successive
     * edits collapse into one upload (REPLACE), only when network is available. */
    fun enqueueTripBackup(tripId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<TripBackupWorker>()
            .setInitialDelay(Duration.ofSeconds(30))
            .setConstraints(constraints)
            .setInputData(workDataOf(TripBackupWorker.KEY_TRIP_ID to tripId))
            .build()
        workManager.enqueueUniqueWork("trip_backup_$tripId", ExistingWorkPolicy.REPLACE, request)
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

    /**
     * Schedule (or cancel) T-3/T-1-day due reminders for every unpaid
     * statement, keyed uniquely per statement so re-running this after every
     * money refresh reschedules cleanly instead of stacking duplicates, and a
     * statement that's since been paid or dropped has its reminders pulled.
     */
    fun scheduleCardDueReminders(statements: List<CardStatement>) {
        statements.forEach { statement ->
            val uniquePrefix = "card-due-${statement.id}"
            val due = statement.dueDate
            if (statement.paidAt != null || due == null) {
                workManager.cancelUniqueWork("$uniquePrefix-t3")
                workManager.cancelUniqueWork("$uniquePrefix-t1")
                return@forEach
            }
            scheduleOneCardDueReminder(uniquePrefix, "t3", "in 3 days", statement, due.minusDays(3))
            scheduleOneCardDueReminder(uniquePrefix, "t1", "tomorrow", statement, due.minusDays(1))
        }
    }

    /** Cancel every scheduled card-due reminder (used when the Settings toggle is turned off). */
    fun cancelAllCardDueReminders() {
        workManager.cancelAllWorkByTag(CARD_DUE_REMINDER_TAG)
    }

    private fun scheduleOneCardDueReminder(
        uniquePrefix: String,
        tag: String,
        dueWord: String,
        statement: CardStatement,
        fireDate: LocalDate,
    ) {
        val uniqueName = "$uniquePrefix-$tag"
        if (fireDate.isBefore(LocalDate.now())) {
            // The reminder date has already passed (e.g. a short statement
            // cycle, or the app was offline) — nothing useful to schedule.
            workManager.cancelUniqueWork(uniqueName)
            return
        }
        val amount = statement.totalDue
            ?.setScale(0, RoundingMode.HALF_UP)
            ?.toPlainString()
            ?.let { "₹$it due" }
            ?: "Payment due"
        val request = OneTimeWorkRequestBuilder<CardDueReminderWorker>()
            .setInitialDelay(initialDelayTo(fireDate, CARD_DUE_REMINDER_TIME))
            .addTag(CARD_DUE_REMINDER_TAG)
            .setInputData(
                workDataOf(
                    CardDueReminderWorker.KEY_TITLE to "${statement.cardLabel} due $dueWord",
                    CardDueReminderWorker.KEY_BODY to "$amount on ${statement.dueDate}.",
                ),
            )
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    /** Delay from now until [time] on [date] (in the past clamps to zero — WorkManager runs it immediately). */
    private fun initialDelayTo(date: LocalDate, time: LocalTime): Duration {
        val now = LocalDateTime.now()
        val target = date.atTime(time)
        return Duration.between(now, target).coerceAtLeast(Duration.ZERO)
    }

    private companion object {
        const val CARD_DUE_REMINDER_TAG = "card_due_reminder"
        val CARD_DUE_REMINDER_TIME: LocalTime = LocalTime.of(9, 0)
    }
}
