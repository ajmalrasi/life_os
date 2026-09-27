package com.lifeos.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.location.TrackingController
import com.lifeos.app.places.data.PlacesRepository
import com.lifeos.app.places.data.SavedPlaceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlacesSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val placesRepository: PlacesRepository,
    private val trackingController: TrackingController,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settingsRepository.placesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val savedPlaces: StateFlow<List<SavedPlaceEntity>> = placesRepository.observeSavedPlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Call only after location permission is granted when turning on. */
    fun setEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setPlacesEnabled(value)
        runCatching { trackingController.reconcile() }
    }

    fun clearSavedPlace(kind: String) = viewModelScope.launch { placesRepository.clearSavedPlace(kind) }

    fun deleteHistory() = viewModelScope.launch { placesRepository.deleteHistory() }
}
