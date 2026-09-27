package com.lifeos.app.trips.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lifeos.app.trips.data.TripRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a trip's saved "active" status honest. The status lives in Room, but the
 * recording lives in [TripLocationService], which stops when the app is updated,
 * force-stopped, or the phone restarts. Run while the app is in the foreground
 * (the only time a location service may be started): a trip still marked active
 * with nothing recording is resumed, or paused if it can no longer record.
 */
@Singleton
class TripTrackingReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TripRepository,
) {
    suspend fun reconcile() {
        val running = TripLocationService.runningTripId
        repository.activeTrips()
            .filter { it.id != running }
            .forEachIndexed { index, trip ->
                // Resume one trip at most; the service records a single trip.
                if (running == null && index == 0 && hasLocationPermission() && !hasEnded(trip.endDate, trip.timezone)) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, TripLocationService::class.java)
                            .putExtra(TripLocationService.EXTRA_TRIP_ID, trip.id),
                    )
                } else {
                    repository.setStatus(trip.id, "paused")
                }
            }
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun hasEnded(endDate: String, timezone: String): Boolean {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        return runCatching { LocalDate.now(zone).isAfter(LocalDate.parse(endDate)) }.getOrDefault(false)
    }
}
