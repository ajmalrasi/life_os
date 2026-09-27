package com.lifeos.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.domain.model.ConnectedMailAccount
import com.lifeos.app.domain.model.SourceStatus
import com.lifeos.app.mail.MailConnectionManager
import androidx.work.WorkInfo
import com.lifeos.app.work.SyncAllWorker
import com.lifeos.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val baseUrl: String = "",
    val apiToken: String = "",
    val saved: Boolean = false,
    val lastSyncAt: Long? = null,
    val pulseReminderEnabled: Boolean = false,
    val pulseReminderHour: Int = 21,
    val cardDueRemindersEnabled: Boolean = true,
    val reopenLastTab: Boolean = true,
    val syncing: Boolean = false,
    /** The step Sync everything is on, while it runs. */
    val syncStep: String? = null,
    val syncMessage: String? = null,
    val syncError: Boolean = false,
    val mailAccounts: List<ConnectedMailAccount> = emptyList(),
    val mailLoading: Boolean = true,
    val connectingPurpose: String? = null,
    val mailMessage: String? = null,
    val mailError: Boolean = false,
    val sourceStatuses: List<SourceStatus> = emptyList(),
    val sourceStatusLoading: Boolean = true,
    val sourceStatusError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val workScheduler: WorkScheduler,
    private val mailConnectionManager: MailConnectionManager,
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Read everything first, then update atomically, so a result that
            // lands while these reads suspend is never overwritten.
            val baseUrl = settingsRepository.apiBaseUrl.first()
            val apiToken = settingsRepository.apiToken.first()
            val lastSyncAt = settingsRepository.lastSyncAt.first()
            val pulseReminderEnabled = settingsRepository.pulseReminderEnabled.first()
            val pulseReminderHour = settingsRepository.pulseReminderHour.first()
            val cardDueRemindersEnabled = settingsRepository.cardDueRemindersEnabled.first()
            val reopenLastTab = settingsRepository.reopenLastTab.first()
            _state.update {
                it.copy(
                    baseUrl = baseUrl,
                    apiToken = apiToken,
                    lastSyncAt = lastSyncAt,
                    pulseReminderEnabled = pulseReminderEnabled,
                    pulseReminderHour = pulseReminderHour,
                    cardDueRemindersEnabled = cardDueRemindersEnabled,
                    reopenLastTab = reopenLastTab,
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.lastSyncAt.collect { lastSyncAt ->
                _state.value = _state.value.copy(lastSyncAt = lastSyncAt)
            }
        }
        viewModelScope.launch {
            settingsRepository.reopenLastTab.collect { reopen ->
                _state.value = _state.value.copy(reopenLastTab = reopen)
            }
        }
        viewModelScope.launch { observeSyncAll() }
        refreshMailAccounts()
        refreshSourceStatuses()
    }

    /** Mirror the background Sync everything job, which outlives this screen
     * and the app. A result is shown only if it's recent, so an old run's
     * "up to date" isn't presented as current. */
    private suspend fun observeSyncAll() {
        var sawRunning = false
        workScheduler.observeSyncAll().collect { infos ->
            val info = infos.lastOrNull() ?: return@collect
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    sawRunning = true
                    val step = info.progress.getString(SyncAllWorker.KEY_STEP)
                        ?: if (info.state == WorkInfo.State.ENQUEUED) "Waiting for a connection" else null
                    _state.update { it.copy(syncing = true, syncStep = step, syncMessage = null, syncError = false) }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val out = info.outputData
                    val finishedAt = out.getLong(SyncAllWorker.KEY_FINISHED_AT, 0L)
                    val recent = System.currentTimeMillis() - finishedAt < RESULT_SHOWN_FOR_MS
                    _state.update {
                        it.copy(
                            syncing = false,
                            syncStep = null,
                            syncMessage = if (sawRunning || recent) out.getString(SyncAllWorker.KEY_MESSAGE) else null,
                            syncError = !out.getBoolean(SyncAllWorker.KEY_SUCCESSFUL, true),
                        )
                    }
                    if (sawRunning) refreshSourceStatuses()
                    sawRunning = false
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    _state.update {
                        it.copy(
                            syncing = false,
                            syncStep = null,
                            syncMessage = if (sawRunning) "Sync stopped before it finished. Tap to run it again." else null,
                            syncError = sawRunning,
                        )
                    }
                    sawRunning = false
                }
            }
        }
    }

    fun onUrlChange(url: String) {
        _state.value = _state.value.copy(baseUrl = url, saved = false)
    }

    fun onTokenChange(token: String) {
        _state.value = _state.value.copy(apiToken = token, saved = false)
    }

    fun save() {
        viewModelScope.launch {
            settingsRepository.setApiBaseUrl(_state.value.baseUrl.trim())
            settingsRepository.setApiToken(_state.value.apiToken.trim())
            _state.value = _state.value.copy(saved = true)
        }
    }

    fun setPulseReminderEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(pulseReminderEnabled = enabled)
        viewModelScope.launch {
            settingsRepository.setPulseReminderEnabled(enabled)
            if (enabled) workScheduler.schedulePulseReminder(_state.value.pulseReminderHour)
            else workScheduler.cancelPulseReminder()
        }
    }

    fun setPulseReminderHour(hour: Int) {
        val clamped = hour.coerceIn(0, 23)
        _state.value = _state.value.copy(pulseReminderHour = clamped)
        viewModelScope.launch {
            settingsRepository.setPulseReminderHour(clamped)
            if (_state.value.pulseReminderEnabled) workScheduler.schedulePulseReminder(clamped)
        }
    }

    /** Turning on takes effect from the next money refresh (app-open or manual
     * sync), which already recomputes reminders from the current statements.
     * Turning off cancels immediately — no need to wait for a refresh. */
    fun setCardDueRemindersEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(cardDueRemindersEnabled = enabled)
        viewModelScope.launch {
            settingsRepository.setCardDueRemindersEnabled(enabled)
            if (!enabled) workScheduler.cancelAllCardDueReminders()
        }
    }

    fun setReopenLastTab(enabled: Boolean) {
        _state.value = _state.value.copy(reopenLastTab = enabled)
        viewModelScope.launch {
            settingsRepository.setReopenLastTab(enabled)
        }
    }

    /** Runs in the background (see [SyncAllWorker]); [observeSyncAll] shows it. */
    fun syncAll() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true, syncStep = null, syncMessage = null, syncError = false) }
        workScheduler.enqueueSyncAll()
    }

    fun refreshMailAccounts() {
        viewModelScope.launch {
            _state.value = _state.value.copy(mailLoading = true, mailError = false)
            runCatching { mailConnectionManager.accounts() }
                .onSuccess { accounts ->
                    _state.value = _state.value.copy(
                        mailAccounts = accounts,
                        mailLoading = false,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        mailLoading = false,
                        mailError = true,
                        mailMessage = error.message ?: "Mail connections could not be loaded.",
                    )
                }
        }
    }

    fun refreshSourceStatuses() {
        viewModelScope.launch {
            _state.value = _state.value.copy(sourceStatusLoading = true, sourceStatusError = false)
            runCatching { analyticsRepository.syncStatus() }
                .onSuccess { statuses ->
                    _state.value = _state.value.copy(
                        sourceStatuses = statuses,
                        sourceStatusLoading = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        sourceStatusLoading = false,
                        sourceStatusError = true,
                    )
                }
        }
    }

    fun connectMail(purpose: String, expectedEmail: String?, forceReauthorize: Boolean) {
        if (_state.value.connectingPurpose != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                connectingPurpose = purpose,
                mailMessage = "Opening Google authorization…",
                mailError = false,
            )
            runCatching {
                mailConnectionManager.connect(purpose, expectedEmail, forceReauthorize)
            }.onSuccess { connected ->
                val accounts = mailConnectionManager.accounts()
                _state.value = _state.value.copy(
                    mailAccounts = accounts,
                    connectingPurpose = null,
                    mailMessage = "${connected.address} is connected to ${purpose.displayName()}.",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    connectingPurpose = null,
                    mailError = true,
                    mailMessage = error.message ?: "Gmail authorization could not be completed.",
                )
            }
        }
    }

    fun stopUsingMail(accountId: Int, purpose: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(connectingPurpose = purpose, mailMessage = null)
            runCatching { mailConnectionManager.stopUsing(accountId, purpose) }
                .onSuccess {
                    val accounts = mailConnectionManager.accounts()
                    _state.value = _state.value.copy(
                        mailAccounts = accounts,
                        connectingPurpose = null,
                        mailMessage = "${purpose.displayName()} mailbox disconnected.",
                        mailError = false,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        connectingPurpose = null,
                        mailMessage = error.message ?: "The mailbox could not be disconnected.",
                        mailError = true,
                    )
                }
        }
    }

    private fun String.displayName(): String = if (this == "finance") "Money" else "Priority"

    private companion object {
        const val RESULT_SHOWN_FOR_MS = 10 * 60 * 1000L
    }
}
