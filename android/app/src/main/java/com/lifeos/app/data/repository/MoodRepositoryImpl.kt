package com.lifeos.app.data.repository

import com.lifeos.app.data.local.MoodDao
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.toDomain
import com.lifeos.app.data.toDto
import com.lifeos.app.data.toEntity
import com.lifeos.app.domain.model.MoodCheckin
import com.lifeos.app.domain.repository.MoodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class MoodRepositoryImpl @Inject constructor(
    private val api: LifeOsApi,
    private val dao: MoodDao,
) : MoodRepository {

    override fun observe(days: Int): Flow<List<MoodCheckin>> =
        dao.observeSince(LocalDate.now().minusDays(days.toLong()).toString())
            .map { list -> list.map { it.toDomain() } }

    override suspend fun submit(checkin: MoodCheckin) {
        // Optimistic local write first (offline-first), then push to backend.
        dao.upsert(checkin.toEntity())
        runCatching { api.submitMood(checkin.toDto()) }
    }

    override suspend fun refresh(days: Int) {
        val remote = api.listMood(days)
        dao.upsertAll(
            remote.map { MoodCheckin(LocalDate.parse(it.day), it.mood, it.energy, it.focus, it.stress, it.note).toEntity() }
        )
    }
}
