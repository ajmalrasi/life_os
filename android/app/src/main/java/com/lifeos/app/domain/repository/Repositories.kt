package com.lifeos.app.domain.repository

import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.ChatAnswer
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.GithubActivity
import com.lifeos.app.domain.model.HealthSample
import com.lifeos.app.domain.model.ExecutionSyncReport
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.MoodCheckin
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Repository interfaces (domain layer). Implementations live in data/ and are
 * the only place that knows about Retrofit / Room / Health Connect.
 *
 * Offline-first contract: reads return cached data via [Flow]; an explicit
 * [refresh] reconciles with the backend and updates the cache.
 */
interface HealthRepository {
    /** Read recent samples from Health Connect (does not upload). */
    suspend fun readRecentSamples(days: Int): List<HealthSample>

    /** Upload samples to the backend; returns number accepted. */
    suspend fun sync(samples: List<HealthSample>): Int
}

interface MoodRepository {
    fun observe(days: Int): Flow<List<MoodCheckin>>
    suspend fun submit(checkin: MoodCheckin)
    suspend fun refresh(days: Int)
}

interface GithubRepository {
    suspend fun submit(activity: GithubActivity)
    suspend fun refresh(days: Int)
}

interface AnalyticsRepository {
    fun observeScores(days: Int): Flow<List<DailyScore>>
    fun observeStates(days: Int): Flow<List<DailyState>>
    fun observeInsights(): Flow<List<Insight>>
    suspend fun refresh(days: Int)
    /** Trigger a backend recompute (nightly analysis on demand). */
    suspend fun recompute(days: Int): ExecutionSyncReport
    suspend fun notifications(): List<AppNotification>
}

interface ChatRepository {
    suspend fun ask(message: String, history: List<ChatTurn>, contextDays: Int): ChatAnswer

    /** Data-grounded starter questions for the Ask screen. Never throws — returns
     *  sensible defaults if the backend is unreachable. */
    suspend fun suggestions(contextDays: Int): List<String>
}

interface SettingsRepository {
    val apiBaseUrl: Flow<String>
    suspend fun setApiBaseUrl(url: String)
    val lastSyncDay: Flow<LocalDate?>
    suspend fun setLastSyncDay(day: LocalDate)

    /** Wall-clock time (epoch millis) of the last successful sync, for display. */
    val lastSyncAt: Flow<Long?>
    suspend fun setLastSyncAt(epochMillis: Long)

    /** Evening Pulse reminder configuration. */
    val pulseReminderEnabled: Flow<Boolean>
    suspend fun setPulseReminderEnabled(enabled: Boolean)
    val pulseReminderHour: Flow<Int>
    suspend fun setPulseReminderHour(hour: Int)
}
