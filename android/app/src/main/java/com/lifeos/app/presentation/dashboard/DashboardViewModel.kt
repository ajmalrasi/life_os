package com.lifeos.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.data.demo.DemoData
import com.lifeos.app.data.demo.DemoMode
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.TodaySummary
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DashboardUiState(
    val latestScore: DailyScore? = null,
    val scoreHistory: List<DailyScore> = emptyList(),
    /** Model-written brief + data freshness; null until the first fetch returns. */
    val today: TodaySummary? = null,
    val todayPulse: DayPulse? = null,
    val pulseSavedMessage: String? = null,
    val pulseSubmitting: Boolean = false,
    val notifications: List<AppNotification> = emptyList(),
    val message: String? = null,
    val usingDemoData: Boolean = false,
    /** Strongest spend↔sleep correlation, if the nightly pipeline found one worth surfacing. */
    val spendSleepInsight: Insight? = null,
    val lastSyncAt: Long? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val moodRepository: MoodRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    private val extra = combine(
        analyticsRepository.observeInsights(),
        settingsRepository.lastSyncAt,
    ) { insights, lastSyncAt -> ExtraState(insights, lastSyncAt) }

    val uiState: StateFlow<DashboardUiState> = combine(
        // Keep up to a year of scores cached so the trend chart can slice any
        // range (1D…1Y) client-side without re-querying.
        analyticsRepository.observeScores(HISTORY_DAYS),
        moodRepository.observe(7),
        extra,
        transient,
    ) { scores, moods, ex, t ->
        val insights = ex.insights
        val useDemo = DemoMode.active && scores.isEmpty()
        val displayScores = if (useDemo) DemoData.scores else scores
        val displayNotifications = if (useDemo && t.notifications.isEmpty()) DemoData.notifications else t.notifications
        val todayPulse = moods.firstOrNull { it.day == LocalDate.now() }?.let { DayPulse.fromCheckin(it) }
            ?: if (useDemo) DemoData.todayPulse else null
        DashboardUiState(
            latestScore = displayScores.firstOrNull(),
            scoreHistory = displayScores,
            today = t.today,
            todayPulse = t.pulseSelected ?: todayPulse,
            pulseSavedMessage = t.pulseSavedMessage,
            pulseSubmitting = t.pulseSubmitting,
            notifications = displayNotifications,
            message = t.message,
            usingDemoData = useDemo,
            spendSleepInsight = insights
                .filter { it.driver == "daily_spend" || it.outcome == "daily_spend" }
                .filter { it.driver == "sleep_score" || it.outcome == "sleep_score" }
                .maxByOrNull { kotlin.math.abs(it.correlation) },
            lastSyncAt = ex.lastSyncAt,
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
        loadToday()
    }

    /** Fetch the brief; while the backend is rewriting it, check back a few times. */
    private fun loadToday() {
        viewModelScope.launch {
            repeat(BRIEF_POLLS) { attempt ->
                if (attempt > 0) delay(BRIEF_POLL_MS)
                val summary = runCatching { analyticsRepository.today() }.getOrNull() ?: return@launch
                transient.value = transient.value.copy(today = summary)
                if (summary.briefStatus != "refreshing") return@launch
                // Scores change alongside the brief, so pick those up too.
                if (attempt > 0) runCatching { analyticsRepository.refresh(HISTORY_DAYS) }
            }
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
                pulseSavedMessage = "${pulse.emoji} Logged: ${pulse.label}",
            )
        }
    }

    private fun setMessage(msg: String) {
        transient.value = transient.value.copy(message = msg)
    }

    private companion object {
        /** Widest trend window the chart can show (1 year). */
        const val HISTORY_DAYS = 365
        const val BRIEF_POLLS = 4
        const val BRIEF_POLL_MS = 20_000L
    }

    private data class ExtraState(
        val insights: List<Insight> = emptyList(),
        val lastSyncAt: Long? = null,
    )

    private data class TransientState(
        val pulseSelected: DayPulse? = null,
        val pulseSavedMessage: String? = null,
        val pulseSubmitting: Boolean = false,
        val notifications: List<AppNotification> = emptyList(),
        val message: String? = null,
        val today: TodaySummary? = null,
    )
}
