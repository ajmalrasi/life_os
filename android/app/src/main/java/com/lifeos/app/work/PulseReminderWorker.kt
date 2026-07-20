package com.lifeos.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.data.local.MoodDao
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.notifications.AppNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Phase 1 #4: evening Pulse nudge.
 *
 * Fires daily (scheduled for the user's configured hour, default 9 PM). If no
 * Pulse check-in exists for today and reminders are enabled, posts a local
 * notification that deep-links into the Pulse tab. Lightweight by design, it
 * never nags if you've already logged.
 */
@HiltWorker
class PulseReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val moodDao: MoodDao,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.pulseReminderEnabled.first()) return Result.success()

        val today = LocalDate.now().toString()
        val alreadyLogged = moodDao.get(today) != null
        if (!alreadyLogged) {
            AppNotifications.show(
                context = applicationContext,
                channelId = AppNotifications.CHANNEL_PULSE,
                notificationId = NOTIFICATION_ID,
                title = "How was today?",
                body = "Take a few seconds to log your Pulse: mood, energy, focus, and stress.",
                openTab = "mood",
            )
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "pulse_reminder_daily"
        private const val NOTIFICATION_ID = 4001
    }
}
