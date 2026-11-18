package com.lifeos.app.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.places.data.MovementUploader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

/** Retries Places uploads independently of the nightly recompute. */
@HiltWorker
class PlacesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val movementUploader: MovementUploader,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        movementUploader.uploadIfEnabled()
        Result.success()
    } catch (error: HttpException) {
        val status = error.code()
        Log.w(TAG, "Places upload returned HTTP $status")
        if (status == 408 || status == 429 || status in 500..599) Result.retry() else Result.failure()
    } catch (error: IOException) {
        Log.w(TAG, "Places upload could not reach the server (${error.javaClass.simpleName})")
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "places_sync"
        private const val TAG = "PlacesSyncWorker"
    }
}
