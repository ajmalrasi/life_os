package com.lifeos.app.presentation.dashboard

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.DemoBanner
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.NotificationCard
import com.lifeos.app.presentation.components.OfflineBanner
import com.lifeos.app.presentation.components.AreaHeader
import com.lifeos.app.presentation.components.SectionHeader
import com.lifeos.app.presentation.components.TrendChart
import com.lifeos.app.presentation.components.VitalsCard
import com.lifeos.app.presentation.theme.LifeOsColors
import com.lifeos.app.domain.model.TodaySummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(
    onOpenMoney: () -> Unit = {},
    onOpenCards: () -> Unit = {},
    onOpenStatement: (Int) -> Unit = {},
    onOpenTrip: (String) -> Unit = {},
    onOpenInsights: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val dateLabel = remember { today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) }
    val overview = state.overview

    Column(modifier = Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        AreaHeader("Today", dateLabel)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.usingDemoData) {
                item { DemoBanner() }
            }

            // The model's read on the day, grounded in facts the backend computed.
            // Offline with nothing fetched yet, the banner below explains instead.
            if (state.today != null || state.message == null) {
                item { TodayBriefCard(state.today) }
            }

            // Today is also the overview: what needs doing, the trip, and Money,
            // all from local caches so it works offline like Money does.
            // The model's workout judgement leads: it is a health problem the
            // money items below cannot show.
            val attention = listOfNotNull(workoutAttention(state.today?.brief)) + overview?.attention.orEmpty()
            if (attention.isNotEmpty()) {
                item {
                    AttentionCard(attention) { item ->
                        when {
                            item.statementId != null -> onOpenStatement(item.statementId)
                            item.target == OverviewTarget.HEALTH -> onOpenInsights()
                            item.target == OverviewTarget.CARDS -> onOpenCards()
                            item.target == OverviewTarget.TRIP -> overview?.trip?.let { onOpenTrip(it.id) }
                            else -> onOpenMoney()
                        }
                    }
                }
            }
            overview?.trip?.let { trip ->
                item { TripOverviewCard(trip, onOpen = { onOpenTrip(trip.id) }) }
            }
            if (overview != null) {
                item { MoneyOverviewCard(overview.money, today, onOpen = onOpenMoney) }
            }

            // Put the actionable daily readings ahead of the longer history.
            item { SectionHeader("Your vitals") }
            item {
                PulseCheckInCard(
                    selected = state.todayPulse,
                    note = state.pulseNote,
                    noteDirty = state.pulseNoteDirty,
                    message = state.pulseSavedMessage,
                    submitting = state.pulseSubmitting,
                    onSelect = viewModel::submitPulse,
                    onNoteChange = viewModel::setPulseNote,
                    onSaveNote = viewModel::savePulseNote,
                    onDiscardNote = viewModel::discardPulseNote,
                )
            }
            item { VitalsCard(history = state.scoreHistory) }
            item { TrendChart(history = state.scoreHistory) }

            if (overview != null && overview.upcoming.isNotEmpty()) {
                item { SectionHeader("Next 14 days") }
                item {
                    UpcomingCard(
                        items = overview.upcoming,
                        onOpenStatement = onOpenStatement,
                        onOpenTrip = onOpenTrip,
                        onOpenMoney = onOpenMoney,
                    )
                }
            }

            // Spend↔sleep is a signal the user can actually act on tonight, so it
            // gets its own card ahead of the general notification feed.
            val spendSleepInsight = state.spendSleepInsight
            if (spendSleepInsight != null) {
                item {
                    LifeOsCard(accent = LifeOsColors.AccentSky) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "SPENDING & SLEEP",
                                style = MaterialTheme.typography.labelSmall,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                            Text(
                                spendSleepInsight.narrative ?: spendSleepInsight.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LifeOsColors.OnBackground,
                            )
                        }
                    }
                }
            }

            if (state.message != null) {
                item {
                    OfflineBanner(lastSyncAt = state.lastSyncAt)
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
}

@Composable
private fun TodayBriefCard(today: TodaySummary?) {
    val brief = today?.brief
    LifeOsCard(accent = LifeOsColors.Primary) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                brief?.state?.takeIf { it != "unknown" }
                    ?.let { "TODAY · " + it.replace('_', ' ').uppercase() }
                    ?: "TODAY",
                style = MaterialTheme.typography.labelSmall,
                color = LifeOsColors.OnSurfaceMuted,
            )
            if (brief != null) {
                Text(brief.headline, style = MaterialTheme.typography.titleLarge, color = LifeOsColors.OnBackground)
                Text(brief.summary, style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnBackground)
                Text(
                    "→ ${brief.suggestion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.Primary,
                )
                // The coach's word on workouts. A flagged note goes to Needs you
                // instead, so it isn't said twice.
                brief.workoutNote?.takeIf { it.isNotBlank() && !brief.workoutAttention }?.let { note ->
                    Text(
                        "Workouts · $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            } else {
                Text(
                    when (today?.briefStatus) {
                        null, "refreshing" -> "Reading today's data..."
                        else -> "Today's read needs the local model, which is switched off."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            val footer = listOfNotNull(
                today?.healthLastReceivedAt?.let { "Health data received ${formatArrival(it)}" },
                if (brief != null && today?.briefStatus == "refreshing") "updating..." else null,
            )
            if (footer.isNotEmpty()) {
                Text(
                    footer.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        }
    }
}

private fun formatArrival(at: Instant): String {
    val local = at.atZone(ZoneId.systemDefault())
    val time = local.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (local.toLocalDate()) {
        LocalDate.now() -> time
        LocalDate.now().minusDays(1) -> "yesterday $time"
        else -> local.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }
}
