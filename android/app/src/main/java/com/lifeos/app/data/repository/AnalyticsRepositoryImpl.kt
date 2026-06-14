package com.lifeos.app.data.repository

import com.lifeos.app.data.local.InsightDao
import com.lifeos.app.data.local.ScoreDao
import com.lifeos.app.data.local.StateDao
import com.lifeos.app.data.toDomain
import com.lifeos.app.data.toEntity
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.ExecutionSyncReport
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.repository.AnalyticsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class AnalyticsRepositoryImpl @Inject constructor(
    private val api: LifeOsApi,
    private val scoreDao: ScoreDao,
    private val stateDao: StateDao,
    private val insightDao: InsightDao,
) : AnalyticsRepository {

    private fun since(days: Int) = LocalDate.now().minusDays(days.toLong()).toString()

    override fun observeScores(days: Int): Flow<List<DailyScore>> =
        scoreDao.observeSince(since(days)).map { list -> list.map { it.toDomain() } }

    override fun observeStates(days: Int): Flow<List<DailyState>> =
        stateDao.observeSince(since(days)).map { list -> list.map { it.toDomain() } }

    override fun observeInsights(): Flow<List<Insight>> =
        insightDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(days: Int) {
        scoreDao.upsertAll(api.scores(days).map { it.toEntity() })
        stateDao.upsertAll(api.states(days).map { it.toEntity() })
        insightDao.clear()
        insightDao.upsertAll(api.insights().map { it.toEntity() })
    }

    override suspend fun recompute(days: Int): ExecutionSyncReport {
        val result = api.recompute(days)
        refresh(days)
        return ExecutionSyncReport(
            daysUpdated = result.executionDaysUpdated,
            reposScanned = result.executionReposScanned,
            source = "git",
            todayCommits = result.executionTodayCommits,
            todayCodingMinutes = result.executionTodayCodingMinutes,
            message = result.executionMessage.orEmpty(),
        )
    }

    override suspend fun notifications(): List<AppNotification> =
        api.notifications().map { it.toDomain() }
}
