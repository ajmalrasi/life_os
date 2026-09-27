package com.lifeos.app.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.trips.data.TripRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether [LocationTrackingService] should run, for Places, a trip, or
 * both, and keeps saved trip status honest. The status lives in Room, but the
 * recording lives in the service, which stops when the app is updated,
 * force-stopped or the phone restarts.
 */
@Singleton
class TrackingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
) {
    /** What should be tracked now. Pauses every active trip that won't be followed. */
    suspend fun desired(runningTripId: String?): TrackingDemand {
        val permitted = hasLocationPermission(context)
        val trips = tripRepository.activeTrips()
        val choice = TripChoice.of(trips.map { it.id }, runningTripId) { id ->
            permitted && trips.first { it.id == id }.let { !hasEnded(it.endDate, it.timezone) }
        }
        choice.pause.forEach { tripRepository.setStatus(it, "paused") }
        return TrackingDemand.of(settingsRepository.placesEnabled.first(), permitted, choice.follow)
    }

    /**
     * Starts, updates or stops the service to match [desired]. Call while the app
     * is in the foreground (resume, a settings change) or right after boot.
     */
    suspend fun reconcile() {
        val running = LocationTrackingService.current
        val wanted = desired(running?.tripId)
        when {
            wanted.shouldRun && wanted != running ->
                ContextCompat.startForegroundService(context, LocationTrackingService.refreshIntent(context))
            !wanted.shouldRun && running != null ->
                context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }

    private fun hasEnded(endDate: String, timezone: String): Boolean {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        return runCatching { LocalDate.now(zone).isAfter(LocalDate.parse(endDate)) }.getOrDefault(false)
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        /** "Allow all the time": needed to restart tracking after the phone restarts. */
        fun hasBackgroundLocationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
