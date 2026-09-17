package com.lifeos.app.trips.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TripBackupRequestDto(
    val title: String,
    @SerialName("base_revision") val baseRevision: Int? = null,
    @SerialName("mutation_id") val mutationId: String,
    val payload: JsonObject,
)

@Serializable
data class TripBackupResponseDto(
    val id: String,
    val title: String,
    val revision: Int,
    val payload: JsonObject,
    @SerialName("updated_at") val updatedAt: String,
)
