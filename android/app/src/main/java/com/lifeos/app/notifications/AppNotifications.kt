package com.lifeos.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lifeos.app.MainActivity
import com.lifeos.app.R

/**
 * Centralized notification channels + a helper to post local notifications.
 *
 * Two channels:
 *  - "pulse_reminder": the evening Pulse nudge (Phase 1 #4)
 *  - "insights": backend-driven alerts delivered via FCM (Phase 1 #5)
 */
object AppNotifications {

    const val CHANNEL_PULSE = "pulse_reminder"
    const val CHANNEL_INSIGHTS = "insights"
    const val CHANNEL_TRIPS = "trip_reminders"
    const val CHANNEL_DATA_HEALTH = "data_health"

    const val EXTRA_OPEN_TAB = "open_tab"
    const val EXTRA_OPEN_ITEM_ID = "priority_item_id"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PULSE,
                "Pulse reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Evening reminder to log your daily Pulse check-in." }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRIPS,
                "Trip reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Reminders you enabled for itinerary items." }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INSIGHTS,
                "Priority & alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Important mail, schedule changes, and LifeOS alerts." }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DATA_HEALTH,
                "Data source health",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "A data source (bank mail, sleep sync, the local model, ...) has gone quiet." }
        )
    }

    /**
     * Post a notification that opens the app (optionally deep-linking to a tab).
     * No-ops silently if the POST_NOTIFICATIONS permission isn't granted.
     */
    fun show(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        openTab: String? = null,
        openItemId: Int? = null,
    ) {
        ensureChannels(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openTab != null) putExtra(EXTRA_OPEN_TAB, openTab)
            if (openItemId != null) putExtra(EXTRA_OPEN_ITEM_ID, openItemId)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }
}
