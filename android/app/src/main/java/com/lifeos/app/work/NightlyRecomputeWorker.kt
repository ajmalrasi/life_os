package com.lifeos.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.notifications.AppNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/**
 * Phase 1 #2: nightly background recompute.
 *
 * Health data arrives solely from the iPhone via Health Auto Export
 * (POST /health/auto-export), which already ingests and recomputes the touched
 * days. This worker does not read/upload Health Connect samples (that code was
 * removed — double-counted activity Apple already provides; single source = HAE).
 * It just triggers a backend recompute of the recent window so the dashboard stays
 * fresh (and correlations are rediscovered) even on days with no live push.
 *
 * Returns [Result.retry] on failure so WorkManager backs off and tries again
 * (e.g. when the LAN backend is briefly unreachable).
 */
@HiltWorker
class NightlyRecomputeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val analyticsRepository: AnalyticsRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Recompute only the recent window: the backend reads 30-day baselines
            // from stored data regardless, and the full history was scored once.
            analyticsRepository.recompute(RECOMPUTE_WINDOW_DAYS)
            settingsRepository.setLastSyncDay(LocalDate.now())
            settingsRepository.setLastSyncAt(System.currentTimeMillis())
            notifyStaleSourcesIfAny()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Best-effort: a stale-source notification is a nice-to-have, never a
     * reason to fail the whole nightly job. Deduped to once per source per
     * calendar day via a tiny local prefs file (this worker runs at most
     * once nightly anyway, but a manual "Sync everything" could also trigger
     * a recompute-adjacent path later — the dedup keeps that safe too). */
    private suspend fun notifyStaleSourcesIfAny() {
        runCatching { analyticsRepository.syncStatus() }
            .onSuccess { statuses ->
                val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val today = LocalDate.now().toString()
                var notificationId = NOTIFICATION_ID_BASE
                for (status in statuses.filter { it.stale }) {
                    val key = "notified_${status.source}"
                    if (prefs.getString(key, null) == today) continue
                    AppNotifications.show(
                        context = applicationContext,
                        channelId = AppNotifications.CHANNEL_DATA_HEALTH,
                        notificationId = notificationId++,
                        title = "${sourceLabel(status.source)} hasn't updated recently",
                        body = status.lastError
                            ?: "No successful sync recorded recently. Check Settings → Data sources.",
                        openTab = "settings",
                    )
                    prefs.edit().putString(key, today).apply()
                }
            }
    }

    private fun sourceLabel(source: String): String = when (source) {
        "bank_mail" -> "Bank/UPI mail"
        "statements" -> "Card statements"
        "priority_mail" -> "Priority mail"
        "sleep_vitals" -> "Sleep & vitals"
        "model" -> "The local model"
        else -> source
    }

    companion object {
        // Kept as the original name so this doesn't duplicate the existing
        // scheduled work under a new unique-work key.
        const val UNIQUE_NAME = "health_sync_nightly"
        private const val RECOMPUTE_WINDOW_DAYS = 30 // backend recompute (kept small for speed)
        private const val MAX_ATTEMPTS = 3
        private const val PREFS_NAME = "data_health_notifications"
        private const val NOTIFICATION_ID_BASE = 4200
    }
}
