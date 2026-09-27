package com.lifeos.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts Places or a tracked trip after the phone restarts. Android lets a
 * location service start from here only with "Allow all the time"; without it
 * tracking resumes the next time the app is opened.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TrackingController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TrackingController.hasBackgroundLocationPermission(context)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { controller.reconcile() }
            } finally {
                pending.finish()
            }
        }
    }
}
