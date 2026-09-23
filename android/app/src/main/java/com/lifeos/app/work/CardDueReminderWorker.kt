package com.lifeos.app.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lifeos.app.notifications.AppNotifications

/**
 * Posts one "card due soon" notification. Scheduled as a one-shot by
 * [WorkScheduler.scheduleCardDueReminders] at T-3 and T-1 days before an
 * unpaid statement's due date — this worker only fires the notification when
 * WorkManager runs it; it does no due-date math itself.
 */
class CardDueReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: "A card statement is due soon."
        AppNotifications.show(
            applicationContext,
            AppNotifications.CHANNEL_CARD_DUE,
            id.hashCode(),
            title,
            body,
            openTab = "money",
        )
        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
    }
}
