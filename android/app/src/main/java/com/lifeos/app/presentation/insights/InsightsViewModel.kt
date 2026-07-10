package com.lifeos.app.presentation.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.data.demo.DemoData
import com.lifeos.app.data.demo.DemoMode
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.SynthesizedInsight
import com.lifeos.app.domain.repository.AnalyticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsUiState(
    val synthesized: List<SynthesizedInsight> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val usingDemoData: Boolean = false,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    val state: StateFlow<InsightsUiState> = combine(
        analyticsRepository.observeSynthesizedInsights(),
        analyticsRepository.observeInsights(),
    ) { synth, real ->
        val useDemo = DemoMode.active && real.isEmpty() && synth.isEmpty()
        InsightsUiState(
            synthesized = if (useDemo) DemoData.synthesizedInsights else synth,
            insights = if (useDemo) DemoData.insights else real,
            usingDemoData = useDemo,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    init {
        viewModelScope.launch { runCatching { analyticsRepository.refresh(90) } }
    }
}
