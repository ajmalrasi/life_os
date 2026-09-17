package com.lifeos.app.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lifeos.app.notifications.AppNotifications

class TripReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: "Open LifeOS to view your trip."
        AppNotifications.show(
            applicationContext,
            AppNotifications.CHANNEL_TRIPS,
            id.hashCode(),
            title,
            body,
            openTab = "trips",
        )
        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
    }
}
