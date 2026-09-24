package com.lifeos.app.domain.repository

import com.lifeos.app.domain.model.PriorityDetails
import com.lifeos.app.domain.model.PriorityItem
import com.lifeos.app.domain.model.PrioritySyncResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface PriorityRepository {
    fun observeItems(): Flow<List<PriorityItem>>
    suspend fun refresh()
    suspend fun syncNow(): PrioritySyncResult
    suspend fun details(itemId: Int): PriorityDetails
    /** The full source email, fetched from Gmail on demand (never cached). */
    suspend fun fullMessage(itemId: Int): String
    suspend fun update(
        itemId: Int,
        state: String? = null,
        labels: List<String>? = null,
        snoozedUntil: Instant? = null,
    ): PriorityItem
    suspend fun feedback(itemId: Int, kind: String): PriorityItem
}
