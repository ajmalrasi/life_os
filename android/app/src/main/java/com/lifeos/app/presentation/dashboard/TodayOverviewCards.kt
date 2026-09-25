package com.lifeos.app.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.money.prettyCategory
import com.lifeos.app.presentation.theme.LifeOsColors
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

/** Up to three things worth acting on, each opening the area that fixes it. */
@Composable
internal fun AttentionCard(items: List<AttentionItem>, onOpen: (AttentionItem) -> Unit) {
    LifeOsCard(accent = LifeOsColors.Warning) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "NEEDS YOU · ${items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = LifeOsColors.Warning,
                fontWeight = FontWeight.Bold,
            )
            items.take(3).forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = LifeOsColors.SurfaceMuted)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(item) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, color = LifeOsColors.OnBackground)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                    }
                    Spacer(Modifier.width(10.dp))
                    AreaChip(
                        when (item.target) {
                            OverviewTarget.MONEY -> "Money"
                            OverviewTarget.CARDS -> "Cards"
                            OverviewTarget.TRIP -> "Trip"
                            OverviewTarget.HEALTH -> "Health"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AreaChip(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = LifeOsColors.Primary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(LifeOsColors.PrimarySoft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The upcoming or current trip: countdown or today's plan, and bills that land on it. */
@Composable
internal fun TripOverviewCard(trip: OverviewTrip, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LifeOsColors.Primary)
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val soft = LifeOsColors.OnPrimary.copy(alpha = 0.78f)
        Text(
            when {
                trip.dayNumber != null -> "TRIP · DAY ${trip.dayNumber} OF ${trip.totalDays}"
                trip.daysUntilStart == 1L -> "TRIP · TOMORROW"
                else -> "TRIP · IN ${trip.daysUntilStart} DAYS"
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = soft,
        )
        Text(trip.title, style = MaterialTheme.typography.titleLarge, color = LifeOsColors.OnPrimary)
        Text(
            "${trip.startDate.format(DayFormat)} to ${trip.endDate.format(DayFormat)} · ${trip.totalDays} days",
            style = MaterialTheme.typography.bodyMedium,
            color = soft,
        )
        trip.dayTitle?.let { title ->
            Spacer(Modifier.height(2.dp))
            Text(
                if (trip.dayNumber != null) "Today" else "Day 1",
                style = MaterialTheme.typography.labelMedium,
                color = soft,
            )
            Text(title, style = MaterialTheme.typography.titleSmall, color = LifeOsColors.OnPrimary)
        }
        trip.nextActivity?.let {
            Text("Next: $it", style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnPrimary)
        }
        if (trip.billsDuringTrip > BigDecimal.ZERO) {
            Text(
                "${formatOverviewAmount(trip.billsDuringTrip)} of card bills fall due during the trip." +
                    if (trip.dayNumber == null) " Pay before you leave?" else "",
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LifeOsColors.OnPrimary.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        trip.trackingStatus?.let {
            Text(
                if (it == "active") "Recording location" else "Location recording paused",
                style = MaterialTheme.typography.labelMedium,
                color = soft,
            )
        }
    }
}

/** Month-to-date spend in Money's default view, with income, cards and budgets. */
@Composable
internal fun MoneyOverviewCard(money: OverviewMoney, today: LocalDate, onOpen: () -> Unit) {
    LifeOsCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "MONEY · ${today.month.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Open Money",
                    tint = LifeOsColors.OnSurfaceMuted,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    formatOverviewAmount(money.monthSpend),
                    style = MaterialTheme.typography.headlineLarge,
                    color = LifeOsColors.OnBackground,
                )
                Text(
                    "spent by day ${money.daysElapsed} · EMI excluded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            val share = money.incomeShare
            val income = money.monthlyIncome
            if (share != null && income != null) {
                IncomeBar(
                    share = share,
                    projected = money.projectedIncomeShare ?: share,
                    goalLine = money.savingsGoalPct?.let { (100 - it) / 100.0 },
                )
                Text(
                    "${(share * 100).roundToInt()}% of ${formatOverviewAmount(income)} income incl. EMI · " +
                        "month end ≈ ${((money.projectedIncomeShare ?: share) * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyTile("Today", money.todaySpend, Modifier.weight(1f))
                MoneyTile("Open cycles", money.openCycleTotal, Modifier.weight(1f))
                MoneyTile("Billed", money.billedTotal, Modifier.weight(1f))
            }
            money.budgets.take(2).forEach { BudgetLine(it) }
        }
    }
}

@Composable
private fun MoneyTile(label: String, amount: BigDecimal, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LifeOsColors.SurfaceMuted)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted, maxLines = 1)
        Text(
            formatOverviewAmount(amount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = LifeOsColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Spent share, the straight-line projection beyond it, and the savings-goal line. */
@Composable
private fun IncomeBar(share: Double, projected: Double, goalLine: Double?) {
    val spent = share.coerceIn(0.0, 1.0).toFloat()
    val ahead = projected.coerceIn(share, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(LifeOsColors.SurfaceMuted),
        )
        Box(
            Modifier.fillMaxWidth(ahead).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(LifeOsColors.PrimarySoft),
        )
        Box(
            Modifier.fillMaxWidth(spent).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(LifeOsColors.Primary),
        )
        goalLine?.takeIf { it in 0.0..1.0 }?.let { line ->
            FractionMarker(line.toFloat(), LifeOsColors.Positive)
        }
    }
}

/** A 2dp vertical tick placed at [fraction] of the parent's width. */
@Composable
private fun FractionMarker(fraction: Float, color: Color) {
    Layout(
        content = { Box(Modifier.width(2.dp).height(14.dp).background(color)) },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, placeable.height) {
            placeable.place((constraints.maxWidth * fraction).roundToInt() - placeable.width / 2, 0)
        }
    }
}

@Composable
private fun BudgetLine(budget: OverviewBudget) {
    val over = budget.spentSoFar > budget.monthlyLimit
    val color = when {
        over -> LifeOsColors.Warning
        budget.onPaceToExceed -> LifeOsColors.AccentAmber
        else -> LifeOsColors.Positive
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Text(
                prettyCategory(budget.category),
                style = MaterialTheme.typography.labelLarge,
                color = LifeOsColors.OnBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    over -> "${(budget.usedShare * 100).roundToInt()}% · over"
                    budget.onPaceToExceed -> "${formatOverviewAmount(budget.spentSoFar)} of ${formatOverviewAmount(budget.monthlyLimit)} · on pace to exceed"
                    else -> "${formatOverviewAmount(budget.spentSoFar)} of ${formatOverviewAmount(budget.monthlyLimit)} · on track"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (over) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
            )
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(LifeOsColors.SurfaceMuted)) {
            Box(
                Modifier.fillMaxWidth(budget.usedShare.coerceIn(0.0, 1.0).toFloat()).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(color),
            )
        }
    }
}

/** Bills, trip days and recurring charges over the next two weeks, grouped by day. */
@Composable
internal fun UpcomingCard(
    items: List<UpcomingItem>,
    onOpenStatement: (Int) -> Unit,
    onOpenTrip: (String) -> Unit,
    onOpenMoney: () -> Unit,
) {
    LifeOsCard {
        Column {
            items.groupBy { it.date }.entries.forEachIndexed { index, (date, dayItems) ->
                if (index > 0) HorizontalDivider(color = LifeOsColors.SurfaceMuted)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            date.dayOfWeek.name.take(3),
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                        Text(
                            date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LifeOsColors.OnBackground,
                        )
                        Text(
                            date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        dayItems.forEach { item ->
                            UpcomingRow(
                                item = item,
                                onClick = {
                                    when {
                                        item.statementId != null -> onOpenStatement(item.statementId)
                                        item.tripId != null -> onOpenTrip(item.tripId)
                                        else -> onOpenMoney()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(item: UpcomingItem, onClick: () -> Unit) {
    val dot = when (item.kind) {
        UpcomingKind.BILL -> LifeOsColors.Warning
        UpcomingKind.TRIP -> LifeOsColors.AccentTeal
        UpcomingKind.RECURRING -> LifeOsColors.MetricExecution
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = LifeOsColors.OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.amount?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                formatOverviewAmount(it),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = LifeOsColors.OnBackground,
            )
        }
    }
}
