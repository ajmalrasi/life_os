package com.lifeos.app.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.domain.repository.MoneyRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SpendWidgetWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val moneyRepository: MoneyRepository,
    private val updater: SpendWidgetUpdater,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Network refresh is best-effort; cached spend still renders when the
        // Jetson or home Wi-Fi is temporarily unavailable.
        runCatching { moneyRepository.refresh(transactionDays = 40, statementMonths = 2) }
        return runCatching {
            updater.updateAll()
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "spend_widget_refresh"
        const val IMMEDIATE_WORK_NAME = "spend_widget_refresh_now"
    }
}
