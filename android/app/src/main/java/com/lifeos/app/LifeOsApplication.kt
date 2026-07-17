package com.lifeos.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.notifications.AppNotifications
import com.lifeos.app.push.PushTokenRegistrar
import com.lifeos.app.sync.LifeOsSyncCoordinator
import com.lifeos.app.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LifeOsApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workScheduler: WorkScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var pushTokenRegistrar: PushTokenRegistrar

    @Inject
    lateinit var appScope: CoroutineScope

    @Inject
    lateinit var syncCoordinator: LifeOsSyncCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppNotifications.ensureChannels(this)

        // Phase 1 #2: passive nightly health sync (idempotent on every launch).
        workScheduler.ensureHealthSyncScheduled()
        workScheduler.ensureSpendWidgetScheduled()

        // Opening the app quietly refreshes its local caches from the Jetson.
        // Source polling and model-backed recomputation stay behind the one
        // explicit "Sync LifeOS" action in Settings so Ask is never starved.
        appScope.launch { syncCoordinator.refreshOnAppOpen() }

        // Phase 1 #4: evening Pulse reminder at the user's configured hour.
        appScope.launch {
            if (settingsRepository.pulseReminderEnabled.first()) {
                workScheduler.schedulePulseReminder(settingsRepository.pulseReminderHour.first())
            } else {
                workScheduler.cancelPulseReminder()
            }
        }

        // Phase 1 #5: register this device for push (no-op until Firebase is set up).
        pushTokenRegistrar.registerCurrentToken()
    }
}
