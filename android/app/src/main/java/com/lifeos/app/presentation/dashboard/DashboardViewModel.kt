package com.lifeos.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.data.demo.DemoData
import com.lifeos.app.data.demo.DemoMode
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.MoneyProfile
import com.lifeos.app.domain.model.RecurringCharge
import com.lifeos.app.domain.model.TodaySummary
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.MoneyRepository
import com.lifeos.app.domain.repository.MoodRepository
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.trips.data.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** Today's check-in note: the draft being typed, else what was saved. */
    val pulseNote: String = "",
    /** True when the note field differs from what is saved. */
    val pulseNoteDirty: Boolean = false,
    val notifications: List<AppNotification> = emptyList(),
    val message: String? = null,
    val usingDemoData: Boolean = false,
    /** Strongest spend↔sleep correlation, if the nightly pipeline found one worth surfacing. */
    val spendSleepInsight: Insight? = null,
    val lastSyncAt: Long? = null,
    /** Money, the trip worth showing, and the next two weeks; from local caches. */
    val overview: TodayOverview? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val moodRepository: MoodRepository,
    private val settingsRepository: SettingsRepository,
    private val moneyRepository: MoneyRepository,
    private val tripRepository: TripRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    /** Income and recurring charges are online-only; a failed fetch just omits them. */
    private val remoteMoney = MutableStateFlow(RemoteMoney())

    private val moneyCaches = combine(
        // A full calendar month plus slack, so month-to-date is always covered.
        moneyRepository.observeTransactions(MONEY_DAYS),
        moneyRepository.observeBudgets(),
        moneyRepository.observeStatements(STATEMENT_MONTHS),
        moneyRepository.observeCurrentCardCycles(),
        remoteMoney,
    ) { transactions, budgets, statements, cycles, remote ->
        val today = LocalDate.now()
        val money = overviewMoney(transactions, budgets, statements, cycles, remote.profile, today)
        Triple(money, statements, remote.recurring)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tripDetail = tripRepository.observeTrips()
        .map { trips -> pickOverviewTrip(trips, LocalDate.now())?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else tripRepository.observeTrip(id) }

    private val overview = combine(moneyCaches, tripDetail) { (money, statements, recurring), detail ->
        val today = LocalDate.now()
        val trip = detail?.let { overviewTrip(it, statements, today) }
        TodayOverview(
            money = money,
            trip = trip,
            upcoming = upcomingItems(statements, trip, recurring, today),
            attention = attentionItems(money, statements, today),
        )
    }

    private val extra = combine(
        analyticsRepository.observeInsights(),
        settingsRepository.lastSyncAt,
        overview,
    ) { insights, lastSyncAt, overview -> ExtraState(insights, lastSyncAt, overview) }

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
        val todayCheckin = moods.firstOrNull { it.day == LocalDate.now() }
        val todayPulse = todayCheckin?.let { DayPulse.fromCheckin(it) }
            ?: if (useDemo) DemoData.todayPulse else null
        val savedNote = todayCheckin?.note.orEmpty()
        DashboardUiState(
            latestScore = displayScores.firstOrNull(),
            scoreHistory = displayScores,
            today = t.today,
            todayPulse = t.pulseSelected ?: todayPulse,
            pulseSavedMessage = t.pulseSavedMessage,
            pulseSubmitting = t.pulseSubmitting,
            pulseNote = t.pulseNoteDraft ?: savedNote,
            pulseNoteDirty = t.pulseNoteDraft != null && t.pulseNoteDraft.trim() != savedNote,
            notifications = displayNotifications,
            message = t.message,
            usingDemoData = useDemo,
            spendSleepInsight = insights
                .filter { it.driver == "daily_spend" || it.outcome == "daily_spend" }
                .filter { it.driver == "sleep_score" || it.outcome == "sleep_score" }
                .maxByOrNull { kotlin.math.abs(it.correlation) },
            lastSyncAt = ex.lastSyncAt,
            overview = ex.overview,
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
                // Fetch first, then update atomically: reading the state before a
                // network call and writing it back after wiped out the brief that
                // arrived meanwhile ("Reading today's data..." forever).
                val notifications = analyticsRepository.notifications()
                transient.update { it.copy(notifications = notifications) }
            }.onFailure { e ->
                val url = runCatching { settingsRepository.apiBaseUrl.first() }.getOrDefault("unknown")
                setMessage("Backend unreachable ($url). Showing cached data.")
            }
        }
        loadToday()
        loadRemoteMoney()
    }

    private fun loadRemoteMoney() {
        viewModelScope.launch {
            val profile = runCatching { moneyRepository.moneyProfile() }.getOrNull()
            val recurring = runCatching { moneyRepository.recurringCharges() }.getOrNull()
            remoteMoney.value = RemoteMoney(
                profile = profile ?: remoteMoney.value.profile,
                recurring = recurring ?: remoteMoney.value.recurring,
            )
        }
    }

    /** Fetch the brief; while the backend is rewriting it, check back a few times. */
    private fun loadToday() {
        viewModelScope.launch {
            repeat(BRIEF_POLLS) { attempt ->
                if (attempt > 0) delay(BRIEF_POLL_MS)
                val summary = runCatching { analyticsRepository.today() }.getOrNull() ?: return@launch
                transient.update { it.copy(today = summary) }
                if (summary.briefStatus != "refreshing") return@launch
                // Scores change alongside the brief, so pick those up too.
                if (attempt > 0) runCatching { analyticsRepository.refresh(HISTORY_DAYS) }
            }
        }
    }

    /** One tap logs (or changes) today's check-in, keeping any note. */
    fun submitPulse(pulse: DayPulse) {
        savePulse(pulse, note = currentNote(), message = "Logged ${pulse.label.lowercase()} for today")
    }

    fun setPulseNote(note: String) {
        transient.update { it.copy(pulseNoteDraft = note, pulseSavedMessage = null) }
    }

    fun discardPulseNote() {
        transient.update { it.copy(pulseNoteDraft = null) }
    }

    /** A note is saved with today's pulse; without a pulse there is nothing to attach it to. */
    fun savePulseNote() {
        val pulse = uiState.value.todayPulse ?: return
        savePulse(pulse, note = currentNote(), message = "Note saved")
    }

    private fun currentNote(): String? =
        (transient.value.pulseNoteDraft ?: uiState.value.pulseNote).trim().ifEmpty { null }

    private fun savePulse(pulse: DayPulse, note: String?, message: String) {
        viewModelScope.launch {
            transient.update { it.copy(pulseSelected = pulse, pulseSubmitting = true, pulseSavedMessage = null) }
            // Saved locally first (offline-first); the backend push and the
            // score recompute are best-effort and catch up on the next sync.
            val saved = runCatching { moodRepository.submit(pulse.toCheckin(note)) }.isSuccess
            transient.update {
                it.copy(
                    pulseSelected = if (saved) pulse else null,
                    pulseSubmitting = false,
                    pulseNoteDraft = if (saved) null else it.pulseNoteDraft,
                    pulseSavedMessage = if (saved) message else "Couldn't save. Try again.",
                )
            }
            if (saved) runCatching { analyticsRepository.recompute(60) }
        }
    }

    private fun setMessage(msg: String) {
        transient.update { it.copy(message = msg) }
    }

    private companion object {
        /** Widest trend window the chart can show (1 year). */
        const val HISTORY_DAYS = 365
        const val BRIEF_POLLS = 4
        const val BRIEF_POLL_MS = 20_000L
        const val MONEY_DAYS = 45
        const val STATEMENT_MONTHS = 7
    }

    private data class ExtraState(
        val insights: List<Insight> = emptyList(),
        val lastSyncAt: Long? = null,
        val overview: TodayOverview? = null,
    )

    private data class RemoteMoney(
        val profile: MoneyProfile? = null,
        val recurring: List<RecurringCharge> = emptyList(),
    )

    private data class TransientState(
        val pulseSelected: DayPulse? = null,
        val pulseSavedMessage: String? = null,
        val pulseSubmitting: Boolean = false,
        /** Non-null while the user is editing the note. */
        val pulseNoteDraft: String? = null,
        val notifications: List<AppNotification> = emptyList(),
        val message: String? = null,
        val today: TodaySummary? = null,
    )
}
