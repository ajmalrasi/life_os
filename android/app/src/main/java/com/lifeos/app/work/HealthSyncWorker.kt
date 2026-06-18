package com.lifeos.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.HealthRepository
import com.lifeos.app.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/**
 * Phase 1 #2 — nightly background health sync.
 *
 * Reads recent Health Connect samples, uploads them, triggers a backend
 * recompute, and records the sync time. Replaces the need to tap "Sync &
 * analyze" daily; the manual button remains as an on-demand fallback.
 *
 * Returns [Result.retry] on failure so WorkManager backs off and tries again
 * (e.g. when the LAN backend is briefly unreachable).
 */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val healthRepository: HealthRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val samples = healthRepository.readRecentSamples(READ_WINDOW_DAYS)
            healthRepository.sync(samples)
            // Recompute only the recent window: a sync only changes recent days,
            // and the backend reads 30-day baselines from stored data regardless.
            // Keeps the post-sync recompute fast (the full history was scored once).
            analyticsRepository.recompute(RECOMPUTE_WINDOW_DAYS)
            settingsRepository.setLastSyncDay(LocalDate.now())
            settingsRepository.setLastSyncAt(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "health_sync_nightly"
        private const val READ_WINDOW_DAYS = 60      // Health Connect look-back (cheap)
        private const val RECOMPUTE_WINDOW_DAYS = 30 // backend recompute (kept small for speed)
        private const val MAX_ATTEMPTS = 3
    }
}
