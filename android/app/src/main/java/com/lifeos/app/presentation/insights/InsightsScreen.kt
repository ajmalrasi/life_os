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
import com.lifeos.app.presentation.components.DemoBanner
import com.lifeos.app.presentation.components.InsightCard
import com.lifeos.app.presentation.components.ScreenHeader
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
        items(state.insights) { InsightCard(it) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
