package com.lifeos.app.work

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lifeos.app.R
import com.lifeos.app.notifications.AppNotifications
import com.lifeos.app.sync.LifeOsSyncCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs Settings' "Sync everything" outside the screen, so closing the app
 * doesn't cancel it halfway. It runs in the foreground with a "Syncing
 * LifeOS" notification naming the current step. If the network drops,
 * WorkManager stops it and runs it again once it's back; every step is
 * idempotent, so starting over is safe. The result goes back to Settings
 * through the output data and, when the app isn't on screen, a notification.
 */
@HiltWorker
class SyncAllWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncCoordinator: LifeOsSyncCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Android 12+ can refuse a foreground start when this reruns from the
        // background (e.g. after the network came back). The sync still runs,
        // just without the ongoing notification.
        runCatching { setForeground(foregroundInfo("Starting")) }

        val result = syncCoordinator.syncAll { step ->
            setProgress(workDataOf(KEY_STEP to step))
            runCatching { setForeground(foregroundInfo(step)) }
        }

        if (!appIsVisible()) {
            AppNotifications.show(
                applicationContext,
                AppNotifications.CHANNEL_SYNC,
                RESULT_NOTIFICATION_ID,
                if (result.successful) "LifeOS synced" else "LifeOS sync incomplete",
                result.message,
                openTab = "settings",
            )
        }
        return Result.success(
            workDataOf(
                KEY_MESSAGE to result.message,
                KEY_SUCCESSFUL to result.successful,
                KEY_FINISHED_AT to System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Starting")

    private fun foregroundInfo(step: String): ForegroundInfo {
        AppNotifications.ensureChannels(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, AppNotifications.CHANNEL_SYNC)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Syncing LifeOS")
            .setContentText(step)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun appIsVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    companion object {
        const val UNIQUE_NAME = "sync_all"
        const val KEY_STEP = "step"
        const val KEY_MESSAGE = "message"
        const val KEY_SUCCESSFUL = "successful"
        const val KEY_FINISHED_AT = "finished_at"
        private const val PROGRESS_NOTIFICATION_ID = 4_101
        private const val RESULT_NOTIFICATION_ID = 4_102
    }
}
