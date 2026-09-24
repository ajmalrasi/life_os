package com.lifeos.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.trips.data.TripRepository
import com.lifeos.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    val autoBackupEnabled: StateFlow<Boolean> = settingsRepository.tripAutoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _backingUpAll = MutableStateFlow(false)
    val backingUpAll: StateFlow<Boolean> = _backingUpAll

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTripAutoBackupEnabled(enabled) }
    }

    fun backupAllNow() {
        viewModelScope.launch {
            _backingUpAll.value = true
            val trips = tripRepository.observeTrips().first()
            trips.forEach { workScheduler.enqueueTripBackup(it.id) }
            _backingUpAll.value = false
        }
    }
}
