package com.lifeos.app.places.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaceStopDto(
    val id: String,
    val name: String,
    val kind: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("arrived_at") val arrivedAt: String,
    @SerialName("departed_at") val departedAt: String? = null,
    @SerialName("trip_title") val tripTitle: String? = null,
)

@Serializable
data class MovementDayDto(
    val day: String,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("vehicle_km") val vehicleKm: Double,
    @SerialName("away_minutes") val awayMinutes: Int? = null,
    @SerialName("late_away_minutes") val lateAwayMinutes: Int? = null,
    @SerialName("farthest_km") val farthestKm: Double? = null,
    @SerialName("trip_title") val tripTitle: String? = null,
    val stops: List<PlaceStopDto> = emptyList(),
)

@Serializable
data class MovementUploadDto(val days: List<MovementDayDto>)

@Serializable
data class MovementUploadResultDto(val days: Int, val stops: Int)
