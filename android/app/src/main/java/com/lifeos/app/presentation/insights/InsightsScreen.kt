package com.lifeos.app.presentation.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.OfflineBanner
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.components.SynthesizedInsightCard
import com.lifeos.app.presentation.theme.LifeOsColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(LifeOsColors.Background)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ScreenHeader(
                    "Insights",
                    "What your mentor thinks, from your money, trips, health and work.",
                )
                if (state.usingDemoData) {
                    Spacer(Modifier.height(10.dp))
                    DemoBanner()
                }
            }
            if (refreshing) {
                item {
                    Text(
                        "Thinking it over… this can take up to a minute.",
                        style = MaterialTheme.typography.labelMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
            if (error != null) {
                item { OfflineBanner(lastSyncAt = null) }
            }
            if (state.synthesized.isEmpty()) {
                item {
                    LifeOsCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "No insights yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = LifeOsColors.OnBackground,
                            )
                            Text(
                                "They're generated after each sync, or pull down to generate now.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                        }
                    }
                }
            } else {
                items(state.synthesized) { SynthesizedInsightCard(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
