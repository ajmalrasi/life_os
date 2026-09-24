package com.lifeos.app.presentation.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.data.demo.DemoData
import com.lifeos.app.data.demo.DemoMode
import com.lifeos.app.domain.model.SynthesizedInsight
import com.lifeos.app.domain.repository.AnalyticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsUiState(
    val synthesized: List<SynthesizedInsight> = emptyList(),
    val usingDemoData: Boolean = false,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    val state: StateFlow<InsightsUiState> = analyticsRepository.observeSynthesizedInsights()
        .map { synth ->
            val useDemo = DemoMode.active && synth.isEmpty()
            InsightsUiState(
                synthesized = if (useDemo) DemoData.synthesizedInsights else synth,
                usingDemoData = useDemo,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Cheap GET only — never hits the LLM, just loads whatever was last generated.
        viewModelScope.launch { runCatching { analyticsRepository.refresh(90) } }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            runCatching { analyticsRepository.refreshInsights() }
                .onFailure { _error.value = "Couldn't refresh insights. Try again in a moment." }
            _refreshing.value = false
        }
    }
}
