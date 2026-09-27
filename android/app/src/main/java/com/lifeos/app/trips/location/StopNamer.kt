package com.lifeos.app.trips.location

import android.content.Context
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Approximate names for stops that are not in the trip plan, from the phone's address lookup. */
@Singleton
class StopNamer @Inject constructor(@ApplicationContext private val context: Context) {
    /** Null when offline or nothing useful is known; the caller keeps the placeholder and retries later. */
    suspend fun name(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull()?.let { StopDetection.approximateName(it.subLocality, it.thoroughfare, it.locality) }
    }
}
