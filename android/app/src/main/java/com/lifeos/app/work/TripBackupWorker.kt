package com.lifeos.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.trips.data.TripRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Backs up one trip to the LifeOS server so Insights and Ask can see it.
 * Enqueued by [WorkScheduler.enqueueTripBackup] after any user edit that
 * changes a trip, when the "Back up trips automatically" setting is on.
 */
@HiltWorker
class TripBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val tripRepository: TripRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return Result.failure()
        return try {
            tripRepository.backup(tripId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TRIP_ID = "trip_id"
        private const val MAX_ATTEMPTS = 3
    }
}
