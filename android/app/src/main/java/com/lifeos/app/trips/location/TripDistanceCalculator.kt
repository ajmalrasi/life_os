package com.lifeos.app.trips.location

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.lifeos.app.trips.data.TripPlaceEntity
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class TripLegDistance(
    val fromPlaceId: String,
    val toPlaceId: String,
    val distanceKm: Double,
    val formattedDistance: String,
)

/**
 * Modular calculator for geodesic distances between trip stops with two-tier caching:
 * 1. Fast in-memory [ConcurrentHashMap] for immediate O(1) lookups during UI rendering and recompositions.
 * 2. Persistent [SharedPreferences] so calculated coordinates are stored once and never recomputed across app restarts.
 */
object TripDistanceCalculator {

    private const val PREFS_NAME = "trip_distance_cache"
    private val memoryCache = ConcurrentHashMap<String, Double>()
    @Volatile private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return prefs ?: synchronized(this) {
            prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
                prefs = it
            }
        }
    }

    private fun cacheKey(lat1: Double, lng1: Double, lat2: Double, lng2: Double): String {
        // Round to 5 decimal places (~1.1 meter accuracy) for robust cache key matching
        val rLat1 = (lat1 * 100000).roundToInt()
        val rLng1 = (lng1 * 100000).roundToInt()
        val rLat2 = (lat2 * 100000).roundToInt()
        val rLng2 = (lng2 * 100000).roundToInt()
        return "${rLat1}_${rLng1}__${rLat2}_${rLng2}"
    }

    /**
     * Computes or retrieves from cache the distance in kilometers between two coordinates.
     */
    fun getDistanceKm(context: Context, lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (lat1 == lat2 && lng1 == lng2) return 0.0

        val key = cacheKey(lat1, lng1, lat2, lng2)

        // 1. Check in-memory cache
        memoryCache[key]?.let { return it }

        // 2. Check persistent disk cache
        val sp = getPrefs(context)
        if (sp.contains(key)) {
            val cached = sp.getFloat(key, 0f).toDouble()
            memoryCache[key] = cached
            return cached
        }

        // 3. Compute geodesic distance using WGS84
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        val distanceKm = results[0] / 1000.0

        // Store in both tiers
        memoryCache[key] = distanceKm
        sp.edit().putFloat(key, distanceKm.toFloat()).apply()

        return distanceKm
    }

    fun getDistanceKm(context: Context, from: TripPlaceEntity, to: TripPlaceEntity): Double? {
        val lat1 = from.latitude ?: return null
        val lng1 = from.longitude ?: return null
        val lat2 = to.latitude ?: return null
        val lng2 = to.longitude ?: return null
        return getDistanceKm(context, lat1, lng1, lat2, lng2)
    }

    fun formatDistance(km: Double): String {
        return when {
            km < 0.05 -> "< 0.1 km"
            km < 1.0 -> "${(km * 1000).roundToInt()} m (${String.format(Locale.US, "%.1f km", km)})"
            km < 10.0 -> String.format(Locale.US, "%.1f km", km)
            else -> String.format(Locale.US, "%.1f km", km)
        }
    }

    /**
     * Formats distance compactly (e.g. for pills, chips, or connectors).
     */
    fun formatCompactDistance(km: Double): String {
        return if (km < 0.1) {
            "< 0.1 km"
        } else {
            String.format(Locale.US, "%.1f km", km)
        }
    }

    /**
     * Computes consecutive legs for a sequence of places (1->2, 2->3, etc.).
     */
    fun calculateConsecutiveLegs(context: Context, places: List<TripPlaceEntity>): List<TripLegDistance> {
        val resolved = places.filter { it.latitude != null && it.longitude != null }
        if (resolved.size < 2) return emptyList()

        val legs = ArrayList<TripLegDistance>(resolved.size - 1)
        for (i in 0 until resolved.size - 1) {
            val from = resolved[i]
            val to = resolved[i + 1]
            val dist = getDistanceKm(context, from, to) ?: continue
            legs.add(
                TripLegDistance(
                    fromPlaceId = from.id,
                    toPlaceId = to.id,
                    distanceKm = dist,
                    formattedDistance = formatCompactDistance(dist),
                )
            )
        }
        return legs
    }

    /**
     * Calculates the total distance across all consecutive places in sequence.
     */
    fun calculateTotalDistance(context: Context, places: List<TripPlaceEntity>): Double {
        val legs = calculateConsecutiveLegs(context, places)
        return legs.sumOf { it.distanceKm }
    }
}
