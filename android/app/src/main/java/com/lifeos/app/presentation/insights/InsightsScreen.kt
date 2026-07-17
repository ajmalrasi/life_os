package com.lifeos.app.presentation.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lifeos.app.presentation.components.DemoBanner
import com.lifeos.app.presentation.components.InsightCard
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.components.SectionHeader
import com.lifeos.app.presentation.components.SynthesizedInsightCard
import com.lifeos.app.presentation.theme.LifeOsColors

@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                "Patterns",
                "Correlations discovered in your own data — grounded in real samples.",
            )
            if (state.usingDemoData) {
                Spacer(Modifier.height(10.dp))
                DemoBanner()
            }
        }
        if (state.synthesized.isNotEmpty()) {
            item { SectionHeader("Key insights") }
            items(state.synthesized) { SynthesizedInsightCard(it) }
        }
        if (state.insights.isEmpty() && state.synthesized.isEmpty()) {
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "No patterns yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = LifeOsColors.OnBackground,
                        )
                        Text(
                            "Keep logging your Pulse; Apple Health arrives through Health Auto Export. Patterns appear once there are enough days to correlate.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            }
        } else if (state.insights.isNotEmpty()) {
            item { SectionHeader("${state.insights.size} correlations") }
            items(state.insights) { InsightCard(it) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
