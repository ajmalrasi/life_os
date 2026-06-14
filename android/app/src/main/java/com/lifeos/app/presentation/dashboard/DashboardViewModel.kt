package com.lifeos.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.GithubActivity
import com.lifeos.app.data.health.HealthConnectManager
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.GithubRepository
import com.lifeos.app.domain.repository.HealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DashboardUiState(
    val latestScore: DailyScore? = null,
    val latestState: DailyState? = null,
    val notifications: List<AppNotification> = emptyList(),
    val syncing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val healthRepository: HealthRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val githubRepository: GithubRepository,
    healthConnectManager: HealthConnectManager,
) : ViewModel() {

    val healthPermissions: Set<String> = healthConnectManager.permissions
    val healthConnectAvailable: Boolean = healthConnectManager.isAvailable()

    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<DashboardUiState> = combine(
        analyticsRepository.observeScores(30),
        analyticsRepository.observeStates(30),
        transient,
    ) { scores, states, t ->
        DashboardUiState(
            latestScore = scores.firstOrNull(),
            latestState = states.firstOrNull(),
            notifications = t.notifications,
            syncing = t.syncing,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                analyticsRepository.refresh(30)
                transient.value = transient.value.copy(notifications = analyticsRepository.notifications())
            }.onFailure { setMessage("Backend unreachable. Showing cached data.") }
        }
    }

    /** Full pipeline: read Health Connect -> upload -> recompute -> refresh cache. */
    fun syncAndAnalyze() {
        viewModelScope.launch {
            transient.value = transient.value.copy(syncing = true, message = null)
            runCatching {
                val samples = healthRepository.readRecentSamples(60)
                val inserted = healthRepository.sync(samples)
                analyticsRepository.recompute(60)
                transient.value = transient.value.copy(
                    notifications = analyticsRepository.notifications(),
                )
                setMessage("Synced $inserted new health records and recomputed insights.")
            }.onFailure { setMessage("Sync failed: ${it.message ?: "unknown error"}") }
            transient.value = transient.value.copy(syncing = false)
        }
    }

    fun logExecution(commits: Int, codingMinutes: Int) {
        viewModelScope.launch {
            runCatching {
                githubRepository.submit(
                    GithubActivity(
                        day = LocalDate.now(),
                        commits = commits,
                        codingMinutes = codingMinutes,
                        activeRepos = if (commits > 0) 1 else 0,
                    )
                )
                analyticsRepository.recompute(60)
                setMessage("Logged today's execution.")
            }.onFailure { setMessage("Could not log execution: ${it.message}") }
        }
    }

    private fun setMessage(msg: String) {
        transient.value = transient.value.copy(message = msg)
    }

    private data class TransientState(
        val notifications: List<AppNotification> = emptyList(),
        val syncing: Boolean = false,
        val message: String? = null,
    )
}
