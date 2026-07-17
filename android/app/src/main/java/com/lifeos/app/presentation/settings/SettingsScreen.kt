package com.lifeos.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.lifeOsFieldColors
import com.lifeos.app.presentation.theme.LifeOsColors

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fieldColors = lifeOsFieldColors()
    var notificationPermissionGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        viewModel.setPulseReminderEnabled(granted)
    }

    // Older builds defaulted the reminder to enabled without ever requesting
    // Android 13+ notification permission. Reconcile that impossible state.
    LaunchedEffect(state.pulseReminderEnabled, notificationPermissionGranted) {
        if (state.pulseReminderEnabled && !notificationPermissionGranted) {
            viewModel.setPulseReminderEnabled(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = LifeOsColors.OnBackground,
                )
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Connection and quiet automation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        }

        SettingsSectionLabel("Connection")
        SettingsGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("LifeOS server", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The app talks to your Jetson over local Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::onUrlChange,
                    label = { Text("Backend address") },
                    placeholder = { Text("http://192.168.3.30:8000/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                )
                if (state.baseUrl.contains("10.0.2.2")) {
                    Text(
                        "10.0.2.2 is emulator-only. Use the Jetson LAN address on your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.Warning,
                    )
                }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LifeOsColors.Primary,
                        contentColor = LifeOsColors.OnPrimary,
                    ),
                ) {
                    Text(if (state.saved) "Saved" else "Save address", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        SettingsSectionLabel("Automation")
        SettingsGroup {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Daily Pulse reminder", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.pulseReminderEnabled) {
                                "Around ${formatHour(state.pulseReminderHour)}, only if today’s Pulse is still empty."
                            } else {
                                "Optional local notification. Nothing is sent off-device."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                    Switch(
                        checked = state.pulseReminderEnabled && notificationPermissionGranted,
                        onCheckedChange = { enabled ->
                            when {
                                !enabled -> viewModel.setPulseReminderEnabled(false)
                                notificationPermissionGranted -> viewModel.setPulseReminderEnabled(true)
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else -> viewModel.setPulseReminderEnabled(true)
                            }
                        },
                    )
                }

                if (state.pulseReminderEnabled && notificationPermissionGranted) {
                    HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Reminder time",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.setPulseReminderHour(state.pulseReminderHour - 1) }) {
                            Icon(Icons.Rounded.Remove, contentDescription = "One hour earlier")
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = LifeOsColors.PrimarySoft,
                        ) {
                            Text(
                                formatHour(state.pulseReminderHour),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = LifeOsColors.Primary,
                            )
                        }
                        IconButton(onClick = { viewModel.setPulseReminderHour(state.pulseReminderHour + 1) }) {
                            Icon(Icons.Rounded.Add, contentDescription = "One hour later")
                        }
                    }
                }

                HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Nightly refresh", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Asks the Jetson to recompute LifeOS overnight. Apple Health continues to arrive through Health Auto Export.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                    Text(
                        "Last completed ${formatLastSync(state.lastSyncAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = LifeOsColors.OnBackground,
                    )
                }
            }
        }

        SettingsSectionLabel("About")
        SettingsGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Private by design", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your data stays on your phone and Jetson. Settings here affect this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LifeOsColors.OnSurfaceMuted,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = LifeOsColors.Surface,
        border = BorderStroke(1.dp, LifeOsColors.SurfaceBorder),
        shadowElevation = 0.dp,
        content = content,
    )
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

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
