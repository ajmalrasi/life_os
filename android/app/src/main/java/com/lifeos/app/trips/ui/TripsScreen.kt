package com.lifeos.app.trips.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.theme.LifeOsColors
import com.lifeos.app.trips.data.TripEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment

@Composable
fun TripsScreen(
    onOpenTrip: (String) -> Unit,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showImport by remember { mutableStateOf(false) }
    var tripToDelete by remember { mutableStateOf<TripEntity?>(null) }
    var json by remember { mutableStateOf("") }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            json = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }
                .getOrDefault("")
            showImport = true
            if (json.isNotBlank()) viewModel.preview(json)
        }
    }

    Column(Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                ScreenHeader("Trips", "Plan the day, remember where you went, and keep everything together.")
            }
            if (state.trips.isEmpty()) {
                item {
                    LifeOsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, tint = LifeOsColors.Primary)
                            Text("Your next trip starts here", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Ask your planning agent for LifeOS trip JSON, then review it here before saving.",
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                            Button(onClick = { showImport = true }) { Text("Import itinerary") }
                        }
                    }
                }
            } else {
                items(state.trips, key = { it.id }) { trip ->
                    LifeOsCard(modifier = Modifier.clickable { onOpenTrip(trip.id) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(trip.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AssistChip(onClick = { onOpenTrip(trip.id) }, label = { Text(tripDisplayStatus(trip)) })
                                    IconButton(onClick = { tripToDelete = trip }) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete trip",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        )
                                    }
                                }
                            }
                            Text(tripListDate(trip), color = LifeOsColors.OnSurfaceMuted)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        if (state.trips.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.End) {
                FloatingActionButton(onClick = { showImport = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Import itinerary")
                }
            }
        }
    }

    tripToDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { tripToDelete = null },
            title = { Text("Delete trip?") },
            text = {
                Text("Are you sure you want to delete \"${trip.title}\"? All planned stops, recorded visits, photos, and tracking data for this trip will be permanently removed.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTrip(trip.id)
                        tripToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { tripToDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; viewModel.cancelPreview() },
            title = { Text(if (state.preview == null) "Import itinerary" else "Review import") },
            text = {
                if (state.preview == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste schema version 1 JSON. Nothing is saved until you review it.")
                        OutlinedTextField(
                            value = json,
                            onValueChange = { json = it },
                            label = { Text("Trip JSON") },
                            minLines = 9,
                            maxLines = 15,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain")) }) {
                            Text("Choose JSON file")
                        }
                    }
                } else {
                    val preview = state.preview!!
                    val existingTrip = remember(preview, state.trips) {
                        preview.originalTripId?.let { id -> state.trips.firstOrNull { it.id == id } }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.editedTitle,
                            onValueChange = viewModel::setEditedTitle,
                            label = { Text("Trip title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${preview.bundle.days.size} days · ${preview.bundle.activities.size} activities · ${preview.bundle.places.size} places",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (existingTrip != null) {
                            LifeOsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "Existing trip with same ID: \"${existingTrip.title}\"",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LifeOsColors.AccentAmber,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { viewModel.setAsNewTrip(true) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = state.asNewTrip,
                                            onClick = { viewModel.setAsNewTrip(true) },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text("Add as new trip", fontWeight = FontWeight.SemiBold)
                                            Text("Creates a separate trip with unique IDs", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { viewModel.setAsNewTrip(false) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = !state.asNewTrip,
                                            onClick = { viewModel.setAsNewTrip(false) },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text("Replace existing trip", fontWeight = FontWeight.SemiBold)
                                            Text("Replaces itinerary stops of \"${existingTrip.title}\"", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                                        }
                                    }
                                }
                            }
                        }
                        if (preview.warnings.isEmpty()) {
                            Text("No import warnings.", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Warnings:", style = MaterialTheme.typography.labelSmall, color = LifeOsColors.AccentAmber)
                                preview.warnings.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (state.preview == null) {
                    Button(onClick = { viewModel.preview(json) }, enabled = json.isNotBlank() && !state.importing) {
                        if (state.importing) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("Review")
                    }
                } else {
                    Button(onClick = { viewModel.savePreview { showImport = false; onOpenTrip(it) } }) {
                        Text(if (state.asNewTrip) "Save new trip" else "Replace trip")
                    }
                }
            },
            dismissButton = {
                if (state.preview == null) TextButton(onClick = { showImport = false }) { Text("Cancel") }
                else OutlinedButton(onClick = viewModel::cancelPreview) { Text("Back") }
            },
        )
    }
}

private fun tripDisplayStatus(trip: TripEntity): String {
    val today = LocalDate.now()
    val start = LocalDate.parse(trip.startDate)
    val end = LocalDate.parse(trip.endDate)
    return when {
        trip.status == "active" -> "Tracking"
        trip.status == "paused" -> "Paused"
        today.isBefore(start) -> {
            val days = ChronoUnit.DAYS.between(today, start)
            if (days == 1L) "Tomorrow" else "In ${days}d"
        }
        today.isAfter(end) -> "Completed"
        else -> "In progress"
    }
}

private fun tripListDate(trip: TripEntity): String {
    val start = LocalDate.parse(trip.startDate)
    val end = LocalDate.parse(trip.endDate)
    val range = if (start.year == end.year && start.month == end.month) {
        "${start.format(DateTimeFormatter.ofPattern("MMM d"))}–${end.format(DateTimeFormatter.ofPattern("d, yyyy"))}"
    } else {
        "${start.format(DateTimeFormatter.ofPattern("MMM d"))}–${end.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
    }
    val days = ChronoUnit.DAYS.between(start, end) + 1
    return "$range · $days days"
}
