package com.lifeos.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifeos.app.notifications.AppNotifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Phase 1 #5 — receives FCM messages from the backend.
 *
 * On token rotation we re-register with the backend; on an incoming message we
 * surface it as a system notification on the "insights" channel.
 *
 * NOTE: requires Firebase to be configured (add google-services.json and apply
 * the com.google.gms.google-services plugin). Until then this class compiles but
 * is never invoked — see android/README or docs for setup.
 */
@AndroidEntryPoint
class LifeOsMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var pushTokenRegistrar: PushTokenRegistrar

    override fun onNewToken(token: String) {
        pushTokenRegistrar.registerToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        val title = notification?.title ?: message.data["title"] ?: "LifeOS"
        val body = notification?.body ?: message.data["body"] ?: ""
        if (body.isBlank()) return

        AppNotifications.show(
            context = this,
            channelId = AppNotifications.CHANNEL_INSIGHTS,
            notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            title = title,
            body = body,
        )
    }
}
