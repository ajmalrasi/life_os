package com.lifeos.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.data.demo.DemoData
import com.lifeos.app.data.demo.DemoMode
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.MoodRepository
import com.lifeos.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DashboardUiState(
    val latestScore: DailyScore? = null,
    val scoreHistory: List<DailyScore> = emptyList(),
    val latestState: DailyState? = null,
    val todayPulse: DayPulse? = null,
    val pulseSavedMessage: String? = null,
    val pulseSubmitting: Boolean = false,
    val notifications: List<AppNotification> = emptyList(),
    val syncing: Boolean = false,
    val message: String? = null,
    val usingDemoData: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val moodRepository: MoodRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<DashboardUiState> = combine(
        // Keep up to a year of scores cached so the trend chart can slice any
        // range (1D…1Y) client-side without re-querying.
        analyticsRepository.observeScores(HISTORY_DAYS),
        analyticsRepository.observeStates(30),
        moodRepository.observe(7),
        transient,
    ) { scores, states, moods, t ->
        val useDemo = DemoMode.active && scores.isEmpty()
        val displayScores = if (useDemo) DemoData.scores else scores
        val displayState = if (useDemo) DemoData.state else states.firstOrNull()
        val displayNotifications = if (useDemo && t.notifications.isEmpty()) DemoData.notifications else t.notifications
        val todayPulse = moods.firstOrNull { it.day == LocalDate.now() }?.let { DayPulse.fromCheckin(it) }
            ?: if (useDemo) DemoData.todayPulse else null
        DashboardUiState(
            latestScore = displayScores.firstOrNull(),
            scoreHistory = displayScores,
            latestState = displayState,
            todayPulse = t.pulseSelected ?: todayPulse,
            pulseSavedMessage = t.pulseSavedMessage,
            pulseSubmitting = t.pulseSubmitting,
            notifications = displayNotifications,
            syncing = t.syncing,
            message = t.message,
            usingDemoData = useDemo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        refresh()
        viewModelScope.launch { runCatching { moodRepository.refresh(7) } }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                analyticsRepository.refresh(HISTORY_DAYS)
                transient.value = transient.value.copy(notifications = analyticsRepository.notifications())
            }.onFailure { e ->
                val url = runCatching { settingsRepository.apiBaseUrl.first() }.getOrDefault("unknown")
                setMessage("Backend unreachable ($url). Showing cached data.")
            }
        }
    }

    /**
     * Recompute + refresh. Health data now arrives from the iPhone via Health
     * Auto Export (POST /health/auto-export), so this no longer reads/uploads
     * Health Connect — it recomputes the recent window and refreshes the cache.
     */
    fun syncAndAnalyze() {
        viewModelScope.launch {
            transient.value = transient.value.copy(syncing = true, message = null)
            runCatching {
                val execution = analyticsRepository.recompute(60)
                settingsRepository.setLastSyncDay(LocalDate.now())
                settingsRepository.setLastSyncAt(System.currentTimeMillis())
                transient.value = transient.value.copy(
                    notifications = analyticsRepository.notifications(),
                )
                val execNote = execution.todayCommits?.let { commits ->
                    val mins = execution.todayCodingMinutes ?: 0
                    " Auto-detected $commits commit(s), ${mins}m coding."
                } ?: ""
                setMessage("Recomputed insights from your latest synced data.$execNote")
            }.onFailure { e ->
                val url = runCatching { settingsRepository.apiBaseUrl.first() }.getOrDefault("unknown")
                val hint = if (url.contains("10.0.2.2")) {
                    " On a real phone, open Settings and set your Mac/Jetson LAN IP (e.g. http://192.168.x.x:8000/)."
                } else ""
                setMessage("Sync failed (${e.message ?: "connection error"}). Backend: $url.$hint")
            }
            transient.value = transient.value.copy(syncing = false)
        }
    }


    fun submitPulse(pulse: DayPulse) {
        viewModelScope.launch {
            transient.value = transient.value.copy(pulseSelected = pulse, pulseSubmitting = true, pulseSavedMessage = null)
            runCatching {
                moodRepository.submit(pulse.toCheckin())
                analyticsRepository.recompute(60)
            }
            transient.value = transient.value.copy(
                pulseSubmitting = false,
                pulseSavedMessage = "${pulse.emoji} Logged — ${pulse.label}",
            )
        }
    }

    private fun setMessage(msg: String) {
        transient.value = transient.value.copy(message = msg)
    }

    private companion object {
        /** Widest trend window the chart can show (1 year). */
        const val HISTORY_DAYS = 365
    }

    private data class TransientState(
        val pulseSelected: DayPulse? = null,
        val pulseSavedMessage: String? = null,
        val pulseSubmitting: Boolean = false,
        val notifications: List<AppNotification> = emptyList(),
        val syncing: Boolean = false,
        val message: String? = null,
    )
}
