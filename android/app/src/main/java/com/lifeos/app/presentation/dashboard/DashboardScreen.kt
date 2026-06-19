package com.lifeos.app.presentation.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.DemoBanner
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.LifeScoreHero
import com.lifeos.app.presentation.components.NotificationCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.components.SectionHeader
import com.lifeos.app.presentation.components.TrendChart
import com.lifeos.app.presentation.components.VitalsCard
import com.lifeos.app.presentation.components.compositeScore
import com.lifeos.app.presentation.theme.LifeOsColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dateLabel = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        // Sync whatever the user allowed — a partial grant is still useful.
        if (granted.any { it in viewModel.healthPermissions }) viewModel.syncAndAnalyze()
    }

    val composite = compositeScore(state.latestScore)
    val prevComposite = state.scoreHistory.getOrNull(1)?.let { compositeScore(it) }
    val delta = if (composite != null && prevComposite != null) composite - prevComposite else null
    // Treat an absent or "unknown" classification as a friendly placeholder rather
    // than surfacing a confusing "Unknown · 0%" chip, and only show a confidence
    // when there's a real state with non-zero confidence.
    val rawState = state.latestState?.state
        ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    val stateLabel = rawState
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
        ?: if (state.latestState == null) "Awaiting data" else "No clear state yet"
    val confidence = rawState?.let {
        state.latestState?.confidence?.let { c -> (c * 100).toInt() }?.takeIf { it > 0 }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader("Today", dateLabel)
            if (state.usingDemoData) {
                Spacer(Modifier.height(10.dp))
                DemoBanner()
            }
        }

        // Hero focal point — composite Life Score, delta, and current state.
        item {
            LifeScoreHero(
                score = composite,
                delta = delta,
                stateLabel = stateLabel,
                confidence = confidence,
            )
        }

        // Historical trend graph with metric toggle — the heart of the redesign.
        item { TrendChart(history = state.scoreHistory) }

        // Why-this-state explanation, only when the model gives a rationale.
        val rationale = state.latestState?.rationale
        if (!rationale.isNullOrBlank()) {
            item {
                LifeOsCard(accent = LifeOsColors.Primary) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "WHY THIS STATE",
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                        Text(
                            rationale,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LifeOsColors.OnBackground,
                        )
                    }
                }
            }
        }

        // All five vitals, grouped. Tap a row for its detail + trend.
        item { SectionHeader("Today's vitals") }
        item { VitalsCard(score = state.latestScore, history = state.scoreHistory) }

        item {
            Button(
                onClick = {
                    if (viewModel.healthConnectAvailable) permissionLauncher.launch(viewModel.healthPermissions)
                    else viewModel.syncAndAnalyze()
                },
                enabled = !state.syncing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LifeOsColors.Primary,
                    contentColor = LifeOsColors.OnPrimary,
                ),
            ) {
                if (state.syncing) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), color = LifeOsColors.OnPrimary)
                Text(if (state.syncing) "Syncing…" else "Sync & analyze", fontWeight = FontWeight.SemiBold)
            }
        }

        if (state.message != null) {
            item {
                Text(state.message!!, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            }
        }

        item { SectionHeader("Notifications") }
        if (state.notifications.isEmpty()) {
            item {
                Text("No nudges yet.", style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnSurfaceMuted)
            }
        } else {
            items(state.notifications) { n ->
                NotificationCard(n.title, n.detail, n.severity)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
