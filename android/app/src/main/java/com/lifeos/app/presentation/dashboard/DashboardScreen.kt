package com.lifeos.app.presentation.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.lifeos.app.presentation.components.NotificationCard
import com.lifeos.app.presentation.components.QuickCheckInCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.components.SectionHeader
import com.lifeos.app.presentation.components.StateHeroCard
import com.lifeos.app.presentation.components.VitalsCard
import com.lifeos.app.presentation.components.WeeklyTrendCard
import com.lifeos.app.presentation.theme.LifeOsColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dateLabel = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(viewModel.healthPermissions)) viewModel.syncAndAnalyze()
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

        // All five vitals in one organized card — no scattered tiles.
        item { VitalsCard(score = state.latestScore) }

        // Weekly trend bar strips — side by side for compact rhythm view.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WeeklyTrendCard(
                    label = "Sleep",
                    values = state.scoreHistory.reversed().map { it.sleepScore },
                    modifier = Modifier.weight(1f),
                )
                WeeklyTrendCard(
                    label = "Execution",
                    values = state.scoreHistory.reversed().map { it.executionScore },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WeeklyTrendCard(
                    label = "Fitness",
                    values = state.scoreHistory.reversed().map { it.fitnessScore },
                    modifier = Modifier.weight(1f),
                )
                WeeklyTrendCard(
                    label = "Wellness",
                    values = state.scoreHistory.reversed().map { it.wellnessScore },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            StateHeroCard(
                stateLabel = state.latestState?.state?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Unknown",
                confidence = state.latestState?.confidence?.let { (it * 100).toInt() },
                rationale = state.latestState?.rationale,
            )
        }

        item {
            QuickCheckInCard(
                selected = state.todayPulse,
                savedLabel = state.pulseSavedMessage,
                enabled = !state.pulseSubmitting,
                onSelect = viewModel::submitPulse,
            )
        }

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
