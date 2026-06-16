package com.lifeos.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.components.SectionHeader
import com.lifeos.app.presentation.components.lifeOsFieldColors
import com.lifeos.app.presentation.theme.LifeOsColors

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fieldColors = lifeOsFieldColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Settings", "Connect LifeOS to your backend on the local network.")

        SectionHeader("Connection")
        LifeOsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Backend URL", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::onUrlChange,
                    label = { Text("FastAPI server") },
                    placeholder = { Text("http://192.168.1.50:8000/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                )
                if (state.baseUrl.contains("10.0.2.2")) {
                    Text(
                        "10.0.2.2 only works on the emulator. Use your Mac/Jetson LAN IP on a real phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.Warning,
                    )
                }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LifeOsColors.Primary,
                        contentColor = LifeOsColors.OnPrimary,
                    ),
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
                if (state.saved) {
                    Text("Saved.", color = LifeOsColors.Primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionHeader("Sync")
        LifeOsCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Background sync", style = MaterialTheme.typography.titleMedium)
                Text(
                    "LifeOS reads Health Connect and recomputes nightly, so you don't have to tap Sync each day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Text(
                    "Last sync: ${formatLastSync(state.lastSyncAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionHeader("Reminders")
        LifeOsCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Evening Pulse reminder", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = state.pulseReminderEnabled,
                        onCheckedChange = viewModel::setPulseReminderEnabled,
                    )
                }
                Text(
                    "Get a nudge to log your Pulse if you haven't by the evening.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                if (state.pulseReminderEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Reminder time", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.setPulseReminderHour(state.pulseReminderHour - 1) }) {
                                Text("−", style = MaterialTheme.typography.titleLarge, color = LifeOsColors.Primary)
                            }
                            Text(formatHour(state.pulseReminderHour), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { viewModel.setPulseReminderHour(state.pulseReminderHour + 1) }) {
                                Text("+", style = MaterialTheme.typography.titleLarge, color = LifeOsColors.Primary)
                            }
                        }
                    }
                }
            }
        }

        SectionHeader("Help")
        LifeOsCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tips", style = MaterialTheme.typography.titleMedium)
                Text("• Emulator → http://10.0.2.2:8000/", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                Text("• Real phone → http://<your-mac-ip>:8000/", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                Text("• OpenClaw URL is set in backend/.env on the Jetson", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatHour(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    val period = if (h < 12) "AM" else "PM"
    val display = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$display:00 $period"
}

private fun formatLastSync(epochMillis: Long?): String {
    if (epochMillis == null) return "never"
    val instant = java.time.Instant.ofEpochMilli(epochMillis)
    val formatter = java.time.format.DateTimeFormatter
        .ofPattern("MMM d, h:mm a")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}
