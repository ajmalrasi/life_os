package com.lifeos.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.repository.AnalyticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-scaffold-level state that isn't owned by any one screen: right now,
 * just whether the navigation drawer's Settings entry needs a warning dot
 * because a data source has gone stale (see Settings' own "Data sources"
 * panel, SettingsViewModel, for the detail view of the same data).
 */
@HiltViewModel
class LifeOsAppViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val _hasStaleSources = MutableStateFlow(false)
    val hasStaleSources: StateFlow<Boolean> = _hasStaleSources.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { analyticsRepository.syncStatus() }
                .onSuccess { statuses -> _hasStaleSources.value = statuses.any { it.stale } }
            // Best-effort: a failed check leaves the dot as-is rather than
            // flashing a false warning.
        }
    }
}
