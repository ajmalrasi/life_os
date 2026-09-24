package com.lifeos.app.presentation.priority

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.PriorityHistoryEntry
import com.lifeos.app.domain.model.PriorityItem
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.SectionHeader
import com.lifeos.app.presentation.theme.LifeOsColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PriorityDetailsScreen(
    viewModel: PriorityDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val details = state.details

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Priority", style = MaterialTheme.typography.headlineMedium)
        }

        if (state.loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
        } else if (details == null) {
            LifeOsCard {
                Text(
                    state.message ?: "This Priority item is unavailable.",
                    color = LifeOsColors.Warning,
                )
            }
        } else {
            val item = details.item
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    PriorityCategories.name(PriorityCategories.of(item)),
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.Primary,
                )
                Text(item.subject, style = MaterialTheme.typography.headlineMedium)
                Text(
                    item.company ?: item.sender,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Text(
                    item.accountLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }

            LifeOsCard(accent = LifeOsColors.Primary) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Why this matters", style = MaterialTheme.typography.titleMedium)
                    Text(item.whyImportant, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SectionHeader("Extracted details")
            LifeOsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailRow("Summary", item.summary)
                    item.role?.let { DetailRow("Role", it) }
                    item.actionText?.let { DetailRow("Action", it) }
                    item.dueAt?.let { DetailRow("Deadline", formatInstant(it)) }
                    item.eventStart?.let { DetailRow("When", formatEventRange(it, item.eventEnd)) }
                    item.location?.let { DetailRow("Location", it) }
                    PriorityCalendarLauncher.meetingPlatform(item.meetingUrl)?.let {
                        DetailRow("Meeting", it)
                    }
                }
            }

            item.meetingUrl?.let { url ->
                OutlinedButton(
                    onClick = { openUri(context, url) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.VideoCall, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        when (val platform = PriorityCalendarLauncher.meetingPlatform(url)) {
                            "Microsoft Teams" -> "Join Teams meeting"
                            "Online meeting", null -> "Join meeting"
                            else -> "Join $platform"
                        },
                    )
                }
            }

            calendarActionLabel(item)?.let { label ->
                Button(
                    onClick = {
                        when {
                            item.eventStart != null && !item.hasCalendarInvite ->
                                PriorityCalendarLauncher.launch(context, item)
                            item.hasCalendarInvite -> openInGmail(context, item.gmailUrl)
                            item.actionUrl != null -> openUri(context, item.actionUrl)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LifeOsColors.Primary,
                        contentColor = LifeOsColors.OnPrimary,
                    ),
                ) {
                    Icon(
                        if (item.eventStart == null) Icons.Rounded.Schedule else Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(label)
                }
            }

            OutlinedButton(
                onClick = { openInGmail(context, item.gmailUrl) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Open Gmail app")
            }

            SectionHeader("Email")
            LifeOsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SelectionContainer {
                        Text(state.fullEmail ?: item.preview, style = MaterialTheme.typography.bodyMedium)
                    }
                    when {
                        state.fullEmail != null -> Unit
                        state.fullEmailLoading -> CircularProgressIndicator(
                            modifier = Modifier.height(24.dp),
                            strokeWidth = 2.dp,
                        )
                        else -> {
                            state.fullEmailError?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = LifeOsColors.Warning)
                            }
                            TextButton(onClick = viewModel::loadFullEmail) { Text("Show full email") }
                        }
                    }
                }
            }

            if (details.history.size > 1) {
                SectionHeader("Thread history")
                details.history.forEach { HistoryRow(it) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::snoozeOneDay,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Snooze")
                }
                Button(
                    onClick = viewModel::markDone,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Done")
                }
            }

            OutlinedButton(
                onClick = viewModel::markNotImportant,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Not important") }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HistoryRow(entry: PriorityHistoryEntry) {
    Surface(
        color = LifeOsColors.Surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.subject, style = MaterialTheme.typography.titleSmall)
            entry.receivedAt?.let {
                Text(formatInstant(it), style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
            }
            Text(entry.preview, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
        }
    }
}

internal fun calendarActionLabel(item: PriorityItem): String? = when {
    item.eventStart != null && item.hasCalendarInvite -> "Open calendar invite"
    item.eventStart != null -> "Add to calendar"
    item.actionUrl != null -> "Choose a time"
    else -> null
}

/**
 * Opens the Gmail app rather than the browser. Neither the Gmail app nor Gmail
 * web on Android can be opened at one message by another app (verified on the
 * S24: https, content:// and search intents all land on the inbox), so the
 * details screen loads the full source email itself.
 */
private fun openInGmail(context: android.content.Context, url: String) {
    val gmail = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(GMAIL_PACKAGE)
    try {
        context.startActivity(gmail)
    } catch (_: ActivityNotFoundException) {
        context.packageManager.getLaunchIntentForPackage(GMAIL_PACKAGE)?.let {
            try {
                context.startActivity(it)
                return
            } catch (_: ActivityNotFoundException) {
                // Fall through to the web link.
            }
        }
        openUri(context, url)
    }
}

private const val GMAIL_PACKAGE = "com.google.android.gm"

private fun openUri(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // The screen remains usable; Android has no handler for this URL.
    }
}

private val detailFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a")
    .withZone(ZoneId.systemDefault())

private fun formatInstant(value: Instant): String = detailFormatter.format(value)

private fun formatEventRange(start: Instant, end: Instant?): String = buildString {
    append(formatInstant(start))
    end?.let { append(" to ${DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(it)}") }
}
