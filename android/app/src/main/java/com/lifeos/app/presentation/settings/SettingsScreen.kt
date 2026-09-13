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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.lifeos.app.domain.model.ConnectedMailAccount

private data class MailDisconnectPrompt(
    val accountId: Int,
    val purpose: String,
    val address: String,
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fieldColors = lifeOsFieldColors()
    var disconnectPrompt by remember { mutableStateOf<MailDisconnectPrompt?>(null) }
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
            Column {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Connection and quiet automation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        }

        SettingsSectionLabel("Sync")
        SettingsGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Sync LifeOS", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Checks Priority mail, bank alerts, and card statements, then refreshes Pulse, Apple Health scores, execution, and insights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Button(
                    onClick = viewModel::syncAll,
                    enabled = !state.syncing,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LifeOsColors.Primary,
                        contentColor = LifeOsColors.OnPrimary,
                    ),
                ) {
                    if (state.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp).size(20.dp),
                            strokeWidth = 2.dp,
                            color = LifeOsColors.OnPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(if (state.syncing) "Syncing LifeOS…" else "Sync everything")
                }
                Text(
                    state.syncMessage ?: "Last refreshed ${formatLastSync(state.lastSyncAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.syncError) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
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

        SettingsSectionLabel("Mail accounts")
        SettingsGroup {
            Column {
                MailConnectionRow(
                    title = "Money email",
                    description = "Bank alerts, UPI transactions, and card statements.",
                    purpose = "finance",
                    account = state.mailAccounts.firstOrNull { "finance" in it.purposes },
                    busy = state.connectingPurpose == "finance",
                    loading = state.mailLoading,
                    onConnect = { current, force ->
                        viewModel.connectMail("finance", current, force)
                    },
                    onDisconnect = { account ->
                        disconnectPrompt = MailDisconnectPrompt(account.id, "finance", account.address)
                    },
                )
                HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                MailConnectionRow(
                    title = "Priority email",
                    description = "Important personal mail, interviews, schedules, and actions.",
                    purpose = "priority",
                    account = state.mailAccounts.firstOrNull { "priority" in it.purposes },
                    busy = state.connectingPurpose == "priority",
                    loading = state.mailLoading,
                    onConnect = { current, force ->
                        viewModel.connectMail("priority", current, force)
                    },
                    onDisconnect = { account ->
                        disconnectPrompt = MailDisconnectPrompt(account.id, "priority", account.address)
                    },
                )
                state.mailMessage?.let { message ->
                    HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                    Text(
                        message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.mailError) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
                    )
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

    disconnectPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { disconnectPrompt = null },
            title = { Text("Stop using this inbox?") },
            text = {
                Text(
                    "LifeOS will stop checking ${prompt.address} for ${prompt.purpose.mailPurposeName()} mail. Existing records stay intact.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        disconnectPrompt = null
                        viewModel.stopUsingMail(prompt.accountId, prompt.purpose)
                    },
                ) { Text("Stop using") }
            },
            dismissButton = {
                TextButton(onClick = { disconnectPrompt = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MailConnectionRow(
    title: String,
    description: String,
    purpose: String,
    account: ConnectedMailAccount?,
    busy: Boolean,
    loading: Boolean,
    onConnect: (currentEmail: String?, forceReauthorize: Boolean) -> Unit,
    onDisconnect: (ConnectedMailAccount) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = LifeOsColors.PrimarySoft) {
                Icon(
                    Icons.Rounded.Mail,
                    contentDescription = null,
                    tint = LifeOsColors.Primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            }
            if (busy || loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }

        when {
            account == null && !loading -> {
                Text("Not connected", style = MaterialTheme.typography.labelMedium, color = LifeOsColors.Warning)
                Button(
                    onClick = { onConnect(null, false) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect Gmail") }
            }
            account != null -> {
                Text(
                    if (account.authorized) "Connected · ${account.address}" else "Authorization needed · ${account.address}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (account.authorized) LifeOsColors.Positive else LifeOsColors.Warning,
                )
                account.priorityLastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (account.authorized) onConnect(null, false)
                            else onConnect(account.address, true)
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (account.authorized) "Change" else "Authorize") }
                    if (account.authorized) {
                        OutlinedButton(
                            onClick = { onConnect(account.address, true) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Reauthorize") }
                    }
                }
                TextButton(
                    onClick = { onDisconnect(account) },
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Stop using for ${purpose.mailPurposeName()}") }
            }
        }
    }
}

private fun String.mailPurposeName(): String = if (this == "finance") "Money" else "Priority"

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
