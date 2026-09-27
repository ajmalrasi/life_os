package com.lifeos.app.trips.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lifeos.app.MainActivity
import com.lifeos.app.R
import com.lifeos.app.trips.data.TripLocationSampleEntity
import com.lifeos.app.trips.data.TripRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class TripLocationService : Service() {
    @Inject lateinit var repository: TripRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    @Volatile private var tripId: String? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = tripId ?: return
            refreshNotification(id)
            result.locations.forEach { location ->
                scope.launch {
                    repository.record(
                        TripLocationSampleEntity(
                            tripId = id,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy,
                            speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                            recordedAt = location.time,
                        )
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val stopping = tripId
            scope.launch { stopping?.let { repository.setStatus(it, "paused") } }
            stopSelf()
            return START_NOT_STICKY
        }
        // A null intent is Android restarting this sticky service after killing
        // the process: resume the trip that is still marked active.
        val requested = intent?.getStringExtra(EXTRA_TRIP_ID)
        val inForeground = runCatching { startForeground(NOTIFICATION_ID, notification()) }.isSuccess
        scope.launch {
            val id = requested ?: repository.activeTrip()?.id
            if (id == null || !inForeground) {
                id?.let { repository.setStatus(it, "paused") }
                withContext(Dispatchers.Main) { stopSelf() }
                return@launch
            }
            tripId = id
            runningTripId = id
            repository.markTracking(id)
            refreshNotification(id)
            val started = withContext(Dispatchers.Main) { requestUpdates() }
            if (!started) {
                repository.setStatus(id, "paused")
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
        return START_STICKY
    }

    /** False when location permission is gone, so the caller can mark the trip paused. */
    @SuppressLint("MissingPermission")
    private fun requestUpdates(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return false
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(50f)
            .setMaxUpdateDelayMillis(120_000L)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
        return true
    }

    /** Says "waiting" before the trip's first day (or after its last) instead of claiming to record. */
    private fun refreshNotification(id: String) {
        scope.launch {
            val waiting = repository.trackingState(id)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(waiting))
        }
    }

    private fun notification(waiting: String? = null): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while LifeOS is recording an active trip."
            }
        )
        val open = PendingIntent.getActivity(
            this, 4100, Intent(this, MainActivity::class.java).putExtra("open_tab", "trips"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 4101, Intent(this, TripLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (waiting == null) "Trip tracking is active" else "Trip tracking is on")
            .setContentText(waiting ?: "LifeOS is recording your route and visited stops.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Pause", stop)
            .build()
    }

    override fun onDestroy() {
        runningTripId = null
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.lifeos.app.trips.STOP"
        const val EXTRA_TRIP_ID = "trip_id"

        /** The trip this process is recording right now, or null. A trip marked
         * active while this is null is stale (the app was updated or killed). */
        @Volatile var runningTripId: String? = null
            private set
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 4100
    }
}
