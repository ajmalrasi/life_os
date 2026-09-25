package com.lifeos.app.presentation.priority

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.PriorityItem
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.AreaHeader
import com.lifeos.app.presentation.components.SectionHeader
import com.lifeos.app.presentation.theme.LifeOsColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PriorityScreen(
    onOpenItem: (Int) -> Unit,
    viewModel: PriorityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val needsAction = state.items.filter { it.state == "needs_action" }
    val scheduled = state.items.filter { it.state == "scheduled" }
    val other = state.items.filter { it !in needsAction && it !in scheduled }
    val statusMessage = state.message

    Column(modifier = Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        AreaHeader(
            title = "Priority",
            subtitle = "Mail that needs you",
            action = {
                IconButton(onClick = viewModel::syncNow, enabled = !state.syncing) {
                    if (state.syncing) {
                        CircularProgressIndicator(modifier = Modifier.padding(9.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Check Priority mail")
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.chips, key = { it.key }) { filter ->
                        val selected = filter.key == state.filter
                        AssistChip(
                            onClick = { viewModel.setFilter(filter.key) },
                            label = { Text("${filter.label} ${filter.count}") },
                            shape = RoundedCornerShape(50),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) LifeOsColors.Primary else LifeOsColors.Surface,
                                labelColor = if (selected) LifeOsColors.OnPrimary else LifeOsColors.OnBackground,
                            ),
                        )
                    }
                }
            }
            if (statusMessage != null) {
                item {
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
            if (state.loading && state.items.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (state.items.isEmpty()) {
                item {
                    LifeOsCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nothing needs attention", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.filter == "all") {
                                    "Important messages will appear here after the Priority mailbox is connected and checked."
                                } else {
                                    "There are no messages in this filter."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                        }
                    }
                }
            } else {
                prioritySection("Needs attention", needsAction, onOpenItem)
                prioritySection("Upcoming", scheduled, onOpenItem)
                prioritySection("Recently important", other, onOpenItem)
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.prioritySection(
    title: String,
    values: List<PriorityItem>,
    onOpenItem: (Int) -> Unit,
) {
    if (values.isEmpty()) return
    item { SectionHeader(title) }
    items(values, key = { it.id }) { item ->
        PriorityMailCard(item, onClick = { onOpenItem(item.id) })
    }
}

@Composable
private fun PriorityMailCard(item: PriorityItem, onClick: () -> Unit) {
    val accent = when {
        item.state == "needs_action" -> LifeOsColors.Warning
        item.state == "scheduled" -> LifeOsColors.Primary
        else -> LifeOsColors.Secondary
    }
    LifeOsCard(modifier = Modifier.clickable(onClick = onClick), accent = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    PriorityCategories.name(PriorityCategories.of(item)),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    relativeTime(item.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            Text(
                item.company ?: item.sender,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subject,
                style = MaterialTheme.typography.bodyMedium,
                color = LifeOsColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val timing = item.dueAt ?: item.eventStart
                if (timing != null) {
                    Text(
                        formatEvent(timing),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Surface(shape = RoundedCornerShape(8.dp), color = LifeOsColors.SurfaceMuted) {
                    Text(
                        item.accountLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

private val eventFormatter = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")
    .withZone(ZoneId.systemDefault())

private fun formatEvent(instant: Instant): String = eventFormatter.format(instant)

private fun relativeTime(instant: Instant): String {
    val duration = Duration.between(instant, Instant.now())
    return when {
        duration.isNegative -> "Now"
        duration.toMinutes() < 60 -> "${duration.toMinutes().coerceAtLeast(1)}m"
        duration.toHours() < 24 -> "${duration.toHours()}h"
        duration.toDays() == 1L -> "Yesterday"
        duration.toDays() < 7 -> "${duration.toDays()}d"
        else -> DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(instant)
    }
}
