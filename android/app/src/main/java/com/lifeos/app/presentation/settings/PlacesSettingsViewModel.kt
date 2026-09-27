package com.lifeos.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.location.TrackingController
import com.lifeos.app.places.data.MovementUploader
import com.lifeos.app.places.data.PlacesRepository
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val movementUploader: MovementUploader,
) : ViewModel() {
    private val _deleteMessage = MutableStateFlow<String?>(null)
    val deleteMessage: StateFlow<String?> = _deleteMessage

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

    /** Deletes on this phone, then the stops and summaries sent to the LifeOS server. */
    fun deleteHistory() = viewModelScope.launch {
        placesRepository.deleteHistory()
        _deleteMessage.value = if (runCatching { movementUploader.deleteFromServer() }.isSuccess) {
            "Deleted from this phone and your LifeOS server."
        } else {
            "Deleted from this phone. Couldn't reach your LifeOS server; delete again when it's reachable."
        }
    }
}
