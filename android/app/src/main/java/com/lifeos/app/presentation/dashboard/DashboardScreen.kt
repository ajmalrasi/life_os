package com.lifeos.app.presentation.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.presentation.components.ScoreCard
import com.lifeos.app.presentation.components.SectionHeader

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(viewModel.healthPermissions)) {
            viewModel.syncAndAnalyze()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Today",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreCard("Sleep", state.latestScore?.sleepScore, modifier = Modifier.weight(1f))
                ScoreCard("Recovery", state.latestScore?.recoveryScore, modifier = Modifier.weight(1f))
                ScoreCard("Execution", state.latestScore?.executionScore, modifier = Modifier.weight(1f))
            }
        }

        item { StateCard(state) }

        item {
            Button(
                onClick = {
                    if (viewModel.healthConnectAvailable) {
                        permissionLauncher.launch(viewModel.healthPermissions)
                    } else {
                        viewModel.syncAndAnalyze()
                    }
                },
                enabled = !state.syncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.syncing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (state.syncing) "Syncing…" else "Sync health & analyze")
            }
        }

        item { ExecutionQuickLog(onLog = viewModel::logExecution) }

        if (state.message != null) {
            item {
                Text(
                    state.message!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        item { SectionHeader("Notifications") }
        if (state.notifications.isEmpty()) {
            item { Text("No proactive nudges yet — keep syncing.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(state.notifications) { NotificationCard(it) }
        }
    }
}

@Composable
private fun StateCard(state: DashboardUiState) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Current state", style = MaterialTheme.typography.labelLarge)
            Text(
                text = state.latestState?.state?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
                    ?: "Unknown",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            state.latestState?.let { s ->
                Text(
                    "Confidence ${(s.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                s.rationale?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun ExecutionQuickLog(onLog: (Int, Int) -> Unit) {
    var commits by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Log today's execution", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = commits,
                    onValueChange = { commits = it.filter(Char::isDigit) },
                    label = { Text("Commits") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit) },
                    label = { Text("Coding min") },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = {
                    onLog(commits.toIntOrNull() ?: 0, minutes.toIntOrNull() ?: 0)
                    commits = ""; minutes = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun NotificationCard(n: AppNotification) {
    val accent = when (n.severity) {
        "warning" -> Color(0xFFFB8500)
        "positive" -> Color(0xFF2EC4B6)
        else -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent)
            Text(n.detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
