package com.lifeos.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/**
 * Phase 1 #2 — nightly background recompute.
 *
 * Health data now arrives solely from the iPhone via Health Auto Export
 * (POST /health/auto-export), which already ingests and recomputes the touched
 * days. This worker no longer reads/uploads Health Connect samples — doing so
 * double-counted activity Apple already provides (single source = HAE). It just
 * triggers a backend recompute of the recent window so the dashboard stays
 * fresh (and correlations are rediscovered) even on days with no live push.
 *
 * Returns [Result.retry] on failure so WorkManager backs off and tries again
 * (e.g. when the LAN backend is briefly unreachable).
 */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
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
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "health_sync_nightly"
        private const val RECOMPUTE_WINDOW_DAYS = 30 // backend recompute (kept small for speed)
        private const val MAX_ATTEMPTS = 3
    }
}
