package com.lifeos.app.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lifeos.app.MainActivity
import com.lifeos.app.R
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.notifications.AppNotifications
import com.lifeos.app.places.data.PlaceSampleEntity
import com.lifeos.app.places.data.PlacesRepository
import com.lifeos.app.trips.data.TripLocationSampleEntity
import com.lifeos.app.trips.data.TripRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The app's only GPS listener. Each location goes to Places (when on) and to
 * the tracked trip (when there is one), so a trip with Places on never makes a
 * second request or a second notification. Each consumer applies its own rules:
 * the trip keeps only its days and area, Places keeps everything.
 */
@AndroidEntryPoint
class LocationTrackingService : Service() {
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var placesRepository: PlacesRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var controller: TrackingController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    // Start commands and location batches are handled one at a time, in order.
    private val commandLock = Mutex()
    private val recordLock = Mutex()
    @Volatile private var demand = TrackingDemand.NONE
    private var updatesRequested = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val serving = demand
            val locations = result.locations.toList()
            scope.launch {
                recordLock.withLock {
                    locations.forEach { location ->
                        val speed = if (location.hasSpeed()) location.speed else null
                        if (serving.places) {
                            placesRepository.record(
                                PlaceSampleEntity(
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    accuracyMeters = location.accuracy,
                                    speedMetersPerSecond = speed,
                                    recordedAt = location.time,
                                )
                            )
                        }
                        serving.tripId?.let { tripId ->
                            tripRepository.record(
                                TripLocationSampleEntity(
                                    tripId = tripId,
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    accuracyMeters = location.accuracy,
                                    speedMetersPerSecond = speed,
                                    recordedAt = location.time,
                                )
                            )
                        }
                    }
                }
                refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: startForegroundService requires it,
        // even when this command ends up stopping the service.
        val inForeground = runCatching {
            startForeground(NOTIFICATION_ID, notification(TrackingNotice.of(demand.takeIf { it.shouldRun } ?: PLACES_ONLY, null, null)))
        }.isSuccess
        // A null intent is Android restarting this sticky service after killing the process.
        val action = intent?.action
        val startTripId = intent?.getStringExtra(EXTRA_TRIP_ID)
        scope.launch {
            commandLock.withLock {
                when (action) {
                    ACTION_PAUSE_TRIP -> (intent.getStringExtra(EXTRA_TRIP_ID) ?: demand.tripId)
                        ?.let { tripRepository.setStatus(it, "paused") }
                    ACTION_PAUSE_PLACES -> settingsRepository.setPlacesEnabled(false)
                }
                if (action == ACTION_START_TRIP) startTripId?.let { tripRepository.markTracking(it) }
                val wanted = controller.desired(demand.tripId)
                if (!inForeground || !wanted.shouldRun) {
                    withContext(Dispatchers.Main) { stopSelf() }
                    return@withLock
                }
                demand = wanted
                current = wanted
                val started = withContext(Dispatchers.Main) { requestUpdates() }
                if (!started) {
                    wanted.tripId?.let { tripRepository.setStatus(it, "paused") }
                    withContext(Dispatchers.Main) { stopSelf() }
                    return@withLock
                }
                refreshNotification()
            }
        }
        return START_STICKY
    }

    /** False when location permission is gone. */
    @SuppressLint("MissingPermission")
    private fun requestUpdates(): Boolean {
        if (!TrackingController.hasLocationPermission(this)) return false
        if (updatesRequested) return true
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(50f)
            .setMaxUpdateDelayMillis(120_000L)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
        updatesRequested = true
        return true
    }

    /** Says "waiting" before a trip's first day (or after its last) instead of claiming to record it. */
    private fun refreshNotification() {
        val serving = demand
        if (!serving.shouldRun) return
        scope.launch {
            val tripId = serving.tripId
            val notice = TrackingNotice.of(
                serving,
                tripTitle = tripId?.let { tripRepository.trip(it)?.title },
                tripWaiting = tripId?.let { tripRepository.trackingState(it) },
            )
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice, serving))
        }
    }

    private fun notification(notice: TrackingNotice, serving: TrackingDemand = demand): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_TRIP_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while LifeOS is recording a trip or noting places."
            }
        )
        val open = PendingIntent.getActivity(
            this, 4100,
            Intent(this, MainActivity::class.java)
                .putExtra(AppNotifications.EXTRA_OPEN_TAB, if (serving.tripId != null) "trips" else "places"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .apply {
                if (notice.pauseTrip) addAction(0, "Pause trip", serviceIntent(4101, ACTION_PAUSE_TRIP))
                if (notice.pausePlaces) addAction(0, "Pause places", serviceIntent(4102, ACTION_PAUSE_PLACES))
            }
            .build()
    }

    private fun serviceIntent(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, LocationTrackingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        current = null
        demand = TrackingDemand.NONE
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_REFRESH = "com.lifeos.app.location.REFRESH"
        private const val ACTION_START_TRIP = "com.lifeos.app.location.START_TRIP"
        private const val ACTION_PAUSE_TRIP = "com.lifeos.app.location.PAUSE_TRIP"
        private const val ACTION_PAUSE_PLACES = "com.lifeos.app.location.PAUSE_PLACES"
        private const val EXTRA_TRIP_ID = "trip_id"
        private const val CHANNEL_ID = "location_tracking"
        private const val OLD_TRIP_CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 4100
        private val PLACES_ONLY = TrackingDemand(places = true, tripId = null)

        /** What this process is tracking right now, or null when the service is not running. */
        @Volatile var current: TrackingDemand? = null
            private set

        /** The trip being recorded right now, or null. */
        val runningTripId: String? get() = current?.tripId

        internal fun refreshIntent(context: Context): Intent =
            Intent(context, LocationTrackingService::class.java).setAction(ACTION_REFRESH)

        /** Starts recording [tripId]; call from the foreground after location permission is granted. */
        fun startTrip(context: Context, tripId: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java)
                    .setAction(ACTION_START_TRIP)
                    .putExtra(EXTRA_TRIP_ID, tripId),
            )
        }

        /** Stops following [tripId]; Places carries on if it is on. The caller also saves the paused status. */
        fun pauseTrip(context: Context, tripId: String) {
            if (current?.tripId != tripId) return
            context.startService(
                Intent(context, LocationTrackingService::class.java)
                    .setAction(ACTION_PAUSE_TRIP)
                    .putExtra(EXTRA_TRIP_ID, tripId),
            )
        }
    }
}
