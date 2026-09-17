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
import javax.inject.Inject

@AndroidEntryPoint
class TripLocationService : Service() {
    @Inject lateinit var repository: TripRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var tripId: String? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = tripId ?: return
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
            scope.launch { tripId?.let { repository.setStatus(it, "paused") } }
            stopSelf()
            return START_NOT_STICKY
        }
        tripId = intent?.getStringExtra(EXTRA_TRIP_ID)
        if (tripId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        scope.launch { repository.setStatus(tripId!!, "active") }
        requestUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(50f)
            .setMaxUpdateDelayMillis(120_000L)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun notification(): android.app.Notification {
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
            .setContentTitle("Trip tracking is active")
            .setContentText("LifeOS is recording your route and visited stops.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Pause", stop)
            .build()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.lifeos.app.trips.STOP"
        const val EXTRA_TRIP_ID = "trip_id"
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 4100
    }
}
