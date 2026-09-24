package com.lifeos.app.data.repository

import com.lifeos.app.data.local.PriorityDao
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.remote.dto.PriorityFeedbackDto
import com.lifeos.app.data.remote.dto.PriorityUpdateDto
import com.lifeos.app.data.toDomain
import com.lifeos.app.data.toEntity
import com.lifeos.app.domain.model.PriorityDetails
import com.lifeos.app.domain.model.PriorityItem
import com.lifeos.app.domain.model.PrioritySyncResult
import com.lifeos.app.domain.repository.PriorityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class PriorityRepositoryImpl @Inject constructor(
    private val api: LifeOsApi,
    private val dao: PriorityDao,
) : PriorityRepository {
    override fun observeItems(): Flow<List<PriorityItem>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun refresh() {
        dao.replaceAll(api.listPriority().map { it.toEntity() })
    }

    override suspend fun syncNow(): PrioritySyncResult {
        val result = api.syncPriority()
        refresh()
        val domain = result.toDomain()
        if (!domain.successful) throw PrioritySyncException(domain.message)
        return domain
    }

    override suspend fun details(itemId: Int): PriorityDetails = api.priorityDetails(itemId).toDomain()

    override suspend fun fullMessage(itemId: Int): String = api.priorityMessage(itemId).body

    override suspend fun update(
        itemId: Int,
        state: String?,
        labels: List<String>?,
        snoozedUntil: Instant?,
    ): PriorityItem {
        val updated = api.updatePriority(
            itemId,
            PriorityUpdateDto(state, labels, snoozedUntil?.toString()),
        )
        dao.upsertAll(listOf(updated.toEntity()))
        return updated.toDomain()
    }

    override suspend fun feedback(itemId: Int, kind: String): PriorityItem {
        val updated = api.priorityFeedback(itemId, PriorityFeedbackDto(kind))
        dao.upsertAll(listOf(updated.toEntity()))
        return updated.toDomain()
    }
}

class PrioritySyncException(message: String) : IllegalStateException(message)
