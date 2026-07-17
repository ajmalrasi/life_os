package com.lifeos.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.sync.LifeOsSyncCoordinator
import com.lifeos.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val baseUrl: String = "",
    val saved: Boolean = false,
    val lastSyncAt: Long? = null,
    val pulseReminderEnabled: Boolean = false,
    val pulseReminderHour: Int = 21,
    val syncing: Boolean = false,
    val syncMessage: String? = null,
    val syncError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val workScheduler: WorkScheduler,
    private val syncCoordinator: LifeOsSyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                baseUrl = settingsRepository.apiBaseUrl.first(),
                lastSyncAt = settingsRepository.lastSyncAt.first(),
                pulseReminderEnabled = settingsRepository.pulseReminderEnabled.first(),
                pulseReminderHour = settingsRepository.pulseReminderHour.first(),
            )
        }
        viewModelScope.launch {
            settingsRepository.lastSyncAt.collect { lastSyncAt ->
                _state.value = _state.value.copy(lastSyncAt = lastSyncAt)
            }
        }
    }

    fun onUrlChange(url: String) {
        _state.value = _state.value.copy(baseUrl = url, saved = false)
    }

    fun save() {
        viewModelScope.launch {
            settingsRepository.setApiBaseUrl(_state.value.baseUrl.trim())
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

    fun syncAll() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(syncing = true, syncMessage = null, syncError = false)
            val result = syncCoordinator.syncAll()
            _state.value = _state.value.copy(
                syncing = false,
                syncMessage = result.message,
                syncError = !result.successful,
                // The coordinator persists this too. Updating the screen state
                // explicitly makes the completion timestamp immediate even if
                // the DataStore collector and navigation recreation race.
                lastSyncAt = if (result.successful) System.currentTimeMillis() else _state.value.lastSyncAt,
            )
        }
    }
}
