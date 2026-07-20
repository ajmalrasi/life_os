package com.lifeos.app.push

import com.google.firebase.messaging.FirebaseMessaging
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.remote.dto.DeviceTokenDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 1 #5: registers this device's FCM token with the backend so it can
 * receive pushed burnout/peak/inactivity alerts.
 *
 * Fail-safe: if Firebase isn't configured yet (no google-services.json), token
 * retrieval throws and we simply skip registration. Wire up Firebase later and
 * this starts working with no code changes. [registerToken] is also called from
 * [com.lifeos.app.push.LifeOsMessagingService.onNewToken] on rotation.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val api: LifeOsApi,
    private val appScope: CoroutineScope,
) {
    /** Fetch the current FCM token (if Firebase is available) and register it. */
    fun registerCurrentToken() {
        appScope.launch {
            val token = runCatching { currentToken() }.getOrNull() ?: return@launch
            registerToken(token)
        }
    }

    fun registerToken(token: String) {
        appScope.launch {
            runCatching { api.registerDeviceToken(DeviceTokenDto(token = token)) }
        }
    }

    private suspend fun currentToken(): String =
        suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
