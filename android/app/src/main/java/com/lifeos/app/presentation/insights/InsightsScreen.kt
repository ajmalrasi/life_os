package com.lifeos.app.presentation.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.Insight
import kotlin.math.abs

@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Discovered patterns", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Correlations LifeOS found in your own data. Each is grounded in real samples.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (insights.isEmpty()) {
            item {
                Text(
                    "No patterns yet. They emerge after ~1–2 weeks of sleep, mood and execution data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(insights) { InsightCard(it) }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    val strength = abs(insight.correlation)
    val strengthLabel = when {
        strength >= 0.7 -> "Strong"
        strength >= 0.5 -> "Moderate"
        else -> "Emerging"
    }
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(insight.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "$strengthLabel · r=${"%+.2f".format(insight.correlation)} · n=${insight.sampleSize}" +
                    if (insight.lagDays > 0) " · ${insight.lagDays}d lag" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
