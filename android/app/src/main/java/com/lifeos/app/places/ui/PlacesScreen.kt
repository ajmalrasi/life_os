package com.lifeos.app.places.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.places.data.NameSource
import com.lifeos.app.places.data.PlaceStopEntity
import com.lifeos.app.places.data.SavedPlaceKind
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.places.domain.DayMovement
import com.lifeos.app.places.domain.DayPayments
import com.lifeos.app.presentation.money.money
import java.math.BigDecimal
import com.lifeos.app.places.domain.TimelineEntry
import com.lifeos.app.presentation.components.AreaHeader
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.theme.LifeOsColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PlacesScreen(
    onOpenSettings: () -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<PlaceStopEntity?>(null) }

    Column(Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        AreaHeader("Places", "Where your days go")
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.loading && !state.enabled) {
                item {
                    LifeOsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Place, contentDescription = null, tint = LifeOsColors.Primary)
                            Text("Places is off", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Turn it on to note where you go each day and which payments you made where. " +
                                    "Locations stay on this phone.",
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                            Button(onClick = onOpenSettings) { Text("Set up Places") }
                        }
                    }
                }
            }
            state.homeSuggestion?.let { suggestion ->
                item {
                    LifeOsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Home, contentDescription = null, tint = LifeOsColors.Primary)
                                Text("Is this home?", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "You spent ${suggestion.nights} of the last 7 nights at the same place. " +
                                    "Saving it as Home shows how far and how long you are out each day.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.confirmHome(suggestion) }) { Text("Yes, it's home") }
                                OutlinedButton(onClick = { viewModel.dismissHome(suggestion) }) { Text("No") }
                            }
                        }
                    }
                }
            }
            item { DayStepper(state.day, state.isToday, viewModel::previousDay, viewModel::nextDay) }
            val hasData = state.entries.isNotEmpty() || (state.movement?.distanceMeters ?: 0.0) > 0.0
            state.movement?.takeIf { state.enabled || hasData }?.let { movement ->
                item { SummaryCard(movement, state.payments, hasHome = state.home != null, tripTitle = state.tripTitle) }
            }
            if (state.entries.isEmpty() && !state.loading && state.enabled) {
                item {
                    Text(
                        if (state.isToday) "No stops yet today." else "Nothing was recorded on this day.",
                        color = LifeOsColors.OnSurfaceMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(state.entries, key = { entryKey(it) }) { entry ->
                when (entry) {
                    is TimelineEntry.Stay -> Column {
                        StayRow(entry, onClick = { selected = entry.stop })
                        state.payments.byStop[entry.stop.id].orEmpty().forEach { PaymentRow(it) }
                    }
                    is TimelineEntry.Move -> Column {
                        MoveRow(entry)
                        state.payments.byMove[entry.from].orEmpty().forEach { PaymentRow(it, onTheMove = true) }
                    }
                }
            }
            if (state.payments.unplaced.isNotEmpty()) {
                item {
                    Text(
                        "Payments without a place",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "No exact time from the bank, or nothing recorded near that time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
                items(state.payments.unplaced, key = { "unplaced-${it.id}" }) { PaymentRow(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    selected?.let { stop ->
        StopDialog(
            stop = stop,
            onDismiss = { selected = null },
            onRename = { viewModel.rename(stop, it); selected = null },
            onSaveAs = { viewModel.saveAs(stop, it); selected = null },
            onRemove = { viewModel.remove(stop); selected = null },
        )
    }
}

private fun entryKey(entry: TimelineEntry): String = when (entry) {
    is TimelineEntry.Stay -> "stay-${entry.stop.id}"
    is TimelineEntry.Move -> "move-${entry.from}"
}

@Composable
private fun DayStepper(day: LocalDate, isToday: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        Text(
            when (day) {
                LocalDate.now() -> "Today"
                LocalDate.now().minusDays(1) -> "Yesterday"
                else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext, enabled = !isToday) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@Composable
private fun SummaryCard(movement: DayMovement, payments: DayPayments, hasHome: Boolean, tripTitle: String?) {
    LifeOsCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tripTitle != null) {
                Text(
                    "$tripTitle trip day",
                    style = MaterialTheme.typography.labelLarge,
                    color = LifeOsColors.Primary,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Travelled", formatDistance(movement.distanceMeters), Modifier.weight(1f))
                Stat("In a vehicle", formatDistance(movement.vehicleMeters), Modifier.weight(1f))
            }
            if (hasHome) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Out of home", formatDuration(movement.awayMillis ?: 0), Modifier.weight(1f))
                    Stat("Farthest", formatDistance(movement.farthestFromHomeMeters ?: 0.0), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Spent", payments.total.money("INR"), Modifier.weight(1f))
                    Stat("Spent while out", (payments.whileOut ?: BigDecimal.ZERO).money("INR"), Modifier.weight(1f))
                }
                val late = movement.lateAwayMillis ?: 0
                if (late > 0) {
                    Text(
                        "${formatDuration(late)} out after 10 PM",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            } else {
                Stat("Spent", payments.total.money("INR"))
                Text(
                    "Save Home to see how long and how far you were out, and what you spent while out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun StayRow(entry: TimelineEntry.Stay, onClick: () -> Unit) {
    val stop = entry.stop
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(12.dp).background(
                if (stop.departedAt == null) LifeOsColors.Positive else LifeOsColors.Primary,
                CircleShape,
            )
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val range = stop.departedAt?.let { "${formatTime(stop.arrivedAt)} to ${formatTime(it)}" }
                ?: "Since ${formatTime(stop.arrivedAt)}"
            val length = formatDuration((stop.departedAt ?: System.currentTimeMillis()) - stop.arrivedAt)
            Text(
                listOfNotNull(range, length, entry.tripTitle?.let { "$it trip" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun PaymentRow(transaction: Transaction, onTheMove: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 26.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            listOfNotNull(
                transaction.txnAt?.let { formatTime(it.toEpochMilli()) },
                transaction.counterparty?.takeIf { it.isNotBlank() } ?: transaction.source,
                if (onTheMove) "on the move" else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = LifeOsColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(transaction.amount.money(transaction.currency), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MoveRow(entry: TimelineEntry.Move) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(19.dp),
    ) {
        Box(Modifier.width(2.dp).height(28.dp).background(LifeOsColors.SurfaceBorder))
        Text(
            buildString {
                append(if (entry.ongoing) "Moving · " else "")
                append(formatDistance(entry.meters))
                append(" · ")
                append(formatDuration(entry.to - entry.from))
            },
            style = MaterialTheme.typography.bodySmall,
            color = LifeOsColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun StopDialog(
    stop: PlaceStopEntity,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onSaveAs: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember(stop.id) {
        mutableStateOf(if (stop.nameSource == NameSource.PENDING) "" else stop.name)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stop.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name this place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSaveAs(SavedPlaceKind.HOME) }) { Text("This is home") }
                    OutlinedButton(onClick = { onSaveAs(SavedPlaceKind.WORK) }) { Text("This is work") }
                }
                TextButton(onClick = onRemove) { Text("Not a stop", color = LifeOsColors.Warning) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save name") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> "${meters.toInt()} m"
    meters < 100_000 -> "%.1f km".format(meters / 1_000)
    else -> "${(meters / 1_000).toInt()} km"
}

internal fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
internal fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormat)
