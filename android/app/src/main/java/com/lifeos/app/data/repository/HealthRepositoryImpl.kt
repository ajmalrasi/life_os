package com.lifeos.app.data.repository

import com.lifeos.app.data.health.HealthConnectManager
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.remote.dto.HealthBatchDto
import com.lifeos.app.data.toDto
import com.lifeos.app.domain.model.HealthSample
import com.lifeos.app.domain.repository.HealthRepository
import javax.inject.Inject

class HealthRepositoryImpl @Inject constructor(
    private val healthConnect: HealthConnectManager,
    private val api: LifeOsApi,
) : HealthRepository {

    override suspend fun readRecentSamples(days: Int): List<HealthSample> {
        if (!healthConnect.isAvailable() || !healthConnect.hasAnyPermissions()) return emptyList()
        return healthConnect.readRecentSamples(days)
    }

    override suspend fun sync(samples: List<HealthSample>): Int {
        if (samples.isEmpty()) return 0
        // Chunk to keep payloads reasonable over LAN.
        var inserted = 0
        samples.chunked(500).forEach { chunk ->
            val result = api.uploadHealth(HealthBatchDto(chunk.map { it.toDto() }))
            inserted += result.inserted
        }
        return inserted
    }
}
