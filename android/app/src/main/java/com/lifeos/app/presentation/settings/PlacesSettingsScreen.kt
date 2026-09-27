package com.lifeos.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.location.TrackingController
import com.lifeos.app.places.data.SavedPlaceKind
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.theme.LifeOsColors

@Composable
fun PlacesSettingsScreen(viewModel: PlacesSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val saved by viewModel.savedPlaces.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    // Permissions and battery settings change outside the app: re-read on every return.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasLocation = remember(resumes) { TrackingController.hasLocationPermission(context) }
    val hasBackground = remember(resumes) { TrackingController.hasBackgroundLocationPermission(context) }
    val batteryUnrestricted = remember(resumes) { ignoresBatteryOptimizations(context) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Tracking works without it; Android just hides the ongoing notification.
        viewModel.setEnabled(true)
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        resumes++
        if (result.values.any { it }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setEnabled(true)
            }
        }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumes++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader("Places", "Where you go, linked with what you spend")

        SettingsGroup {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Note places", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Records where you stop through the day. Your route stays on this phone for 30 days; " +
                                "a silent notification shows while it runs. A tracked trip shares the same recording.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                    Switch(
                        checked = enabled && hasLocation,
                        onCheckedChange = { on ->
                            when {
                                !on -> viewModel.setEnabled(false)
                                hasLocation -> viewModel.setEnabled(true)
                                else -> locationLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                )
                            }
                        },
                    )
                }
                if (enabled && hasLocation) {
                    HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                    StatusRow(
                        title = "Keep going after a restart",
                        detail = if (hasBackground) {
                            "Allowed. Places starts again when the phone restarts."
                        } else {
                            "Needs location access \"Allow all the time\". Until then, Places starts again when you open LifeOS."
                        },
                        ok = hasBackground,
                        action = if (hasBackground || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null else "Allow" to {
                            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        },
                    )
                    HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                    StatusRow(
                        title = "Battery",
                        detail = if (batteryUnrestricted) {
                            "Unrestricted. Samsung won't stop Places in the background."
                        } else {
                            "Set LifeOS battery use to Unrestricted, or Samsung may stop Places after a while."
                        },
                        ok = batteryUnrestricted,
                        action = if (batteryUnrestricted) null else "Open" to { openAppSettings(context) },
                    )
                }
            }
        }

        SettingsSectionLabel("Saved places")
        SettingsGroup {
            Column {
                listOf(SavedPlaceKind.HOME to "Home", SavedPlaceKind.WORK to "Work").forEachIndexed { index, (kind, label) ->
                    if (index > 0) HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                    val place = saved.firstOrNull { it.kind == kind }
                    StatusRow(
                        title = label,
                        detail = if (place != null) "Saved" else "Not set. Tap a stop in Places and choose \"This is ${label.lowercase()}\".",
                        ok = place != null,
                        action = if (place != null) "Clear" to { viewModel.clearSavedPlace(kind) } else null,
                    )
                }
            }
        }

        SettingsSectionLabel("History")
        SettingsGroup {
            StatusRow(
                title = "Delete location history",
                detail = "Removes every recorded location, stop and saved place from this phone.",
                ok = true,
                action = "Delete" to { confirmDelete = true },
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete location history?") },
            text = { Text("Every recorded location, stop, and saved place on this phone is deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteHistory(); confirmDelete = false }) {
                    Text("Delete", color = LifeOsColors.Warning)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusRow(title: String, detail: String, ok: Boolean, action: Pair<String, () -> Unit>?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) LifeOsColors.OnSurfaceMuted else LifeOsColors.Warning,
            )
        }
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}

private fun ignoresBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
