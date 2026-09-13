package com.lifeos.app.presentation.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.theme.LifeOsColors
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal data class SpendBucket(
    val start: LocalDate,
    val end: LocalDate,
    val amount: BigDecimal,
    val axisLabel: String,
    val detailLabel: String,
)

/** Days for week/month, seven-day blocks for six months, calendar months for year. */
internal fun spendTrendSeries(
    transactions: List<Transaction>,
    period: SpendPeriod,
    start: LocalDate,
    end: LocalDate,
): List<SpendBucket> {
    require(!end.isBefore(start))
    val totals = transactions
        .filter { !it.txnDate.isBefore(start) && !it.txnDate.isAfter(end) }
        .groupBy { it.txnDate }
        .mapValues { (_, items) -> items.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount } }

    fun total(bucketStart: LocalDate, bucketEnd: LocalDate): BigDecimal =
        generateSequence(bucketStart) { it.plusDays(1).takeIf { day -> !day.isAfter(bucketEnd) } }
            .fold(BigDecimal.ZERO) { sum, day -> sum + (totals[day] ?: BigDecimal.ZERO) }

    return when (period) {
        SpendPeriod.WEEK, SpendPeriod.MONTH -> List(ChronoUnit.DAYS.between(start, end).toInt() + 1) { offset ->
            val day = start.plusDays(offset.toLong())
            SpendBucket(
                day, day, totals[day] ?: BigDecimal.ZERO,
                day.dayOfMonth.toString(),
                day.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
            )
        }
        SpendPeriod.SIX_MONTHS -> buildList {
            var bucketStart = start
            while (!bucketStart.isAfter(end)) {
                val bucketEnd = minOf(bucketStart.plusDays(6), end)
                add(
                    SpendBucket(
                        bucketStart, bucketEnd, total(bucketStart, bucketEnd),
                        bucketStart.format(DateTimeFormatter.ofPattern("d MMM")),
                        "${bucketStart.format(DateTimeFormatter.ofPattern("d MMM"))} to ${bucketEnd.format(DateTimeFormatter.ofPattern("d MMM"))}",
                    ),
                )
                bucketStart = bucketEnd.plusDays(1)
            }
        }
        SpendPeriod.YEAR -> buildList {
            var monthStart = start.withDayOfMonth(1)
            while (!monthStart.isAfter(end)) {
                val monthEnd = minOf(monthStart.withDayOfMonth(monthStart.lengthOfMonth()), end)
                add(
                    SpendBucket(
                        monthStart, monthEnd, total(maxOf(monthStart, start), monthEnd),
                        monthStart.format(DateTimeFormatter.ofPattern("MMM")),
                        monthStart.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    ),
                )
                monthStart = monthStart.plusMonths(1)
            }
        }
        else -> emptyList()
    }
}

@Composable
internal fun SpendTrendChart(
    transactions: List<Transaction>,
    period: SpendPeriod,
    start: LocalDate,
    end: LocalDate,
) {
    val points = remember(transactions, period, start, end) { spendTrendSeries(transactions, period, start, end) }
    if (points.isEmpty()) return

    val today = LocalDate.now()
    val initialSelection = points.indexOfFirst { !today.isBefore(it.start) && !today.isAfter(it.end) }
        .takeIf { it >= 0 }
        ?: points.indexOfLast { it.amount > BigDecimal.ZERO }.takeIf { it >= 0 }
        ?: points.lastIndex
    var selectedIndex by rememberSaveable(period, start, end) { mutableIntStateOf(initialSelection) }
    selectedIndex = selectedIndex.coerceIn(points.indices)
    val selected = points[selectedIndex]
    val peak = points.maxOf { it.amount }.coerceAtLeast(BigDecimal.ONE)
    val primary = LifeOsColors.Primary
    val grid = LifeOsColors.ChartGrid
    val title = when (period) {
        SpendPeriod.SIX_MONTHS -> "Weekly spending"
        SpendPeriod.YEAR -> "Monthly spending"
        else -> "Daily spending"
    }
    val rangeLabel = when (period) {
        SpendPeriod.WEEK -> "${start.format(DateTimeFormatter.ofPattern("d MMM"))} to ${end.format(DateTimeFormatter.ofPattern("d MMM"))}"
        SpendPeriod.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        SpendPeriod.SIX_MONTHS -> "${start.format(DateTimeFormatter.ofPattern("MMM yyyy"))} to ${end.format(DateTimeFormatter.ofPattern("MMM yyyy"))}"
        SpendPeriod.YEAR -> start.year.toString()
        else -> ""
    }
    val labelIndices = listOf(0, points.lastIndex / 4, points.lastIndex / 2, points.lastIndex * 3 / 4, points.lastIndex).distinct()

    LifeOsCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rangeLabel, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                Text("Peak ${formatSpendAmount(points.maxOf { it.amount })}", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            }
            Canvas(
                Modifier.fillMaxWidth().height(148.dp)
                    .semantics { contentDescription = "$title chart for $rangeLabel. Use the period buttons below to explore each amount." }
                    .pointerInput(points) {
                        detectTapGestures { tap ->
                            selectedIndex = (tap.x / size.width * points.size).toInt().coerceIn(points.indices)
                        }
                    },
            ) {
                val slot = size.width / points.size
                val baseline = size.height - 3.dp.toPx()
                val plotHeight = baseline - 4.dp.toPx()
                repeat(3) { index ->
                    val y = baseline - plotHeight * index / 2f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                points.forEachIndexed { index, point ->
                    val fraction = (point.amount.toDouble() / peak.toDouble()).toFloat().coerceIn(0f, 1f)
                    val barHeight = if (fraction > 0f) (plotHeight * fraction).coerceAtLeast(2.dp.toPx()) else 2.dp.toPx()
                    drawRoundRect(
                        color = if (point.amount.signum() == 0) grid else primary.copy(alpha = if (index == selectedIndex) 1f else 0.55f),
                        topLeft = Offset(slot * index + slot * 0.18f, baseline - barHeight),
                        size = Size(slot * 0.64f, barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    if (index == selectedIndex) {
                        drawCircle(primary, radius = 2.dp.toPx(), center = Offset(slot * (index + 0.5f), size.height - 1.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labelIndices.forEach { index ->
                    Text(points[index].axisLabel, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedIndex-- }, enabled = selectedIndex > 0) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous period")
                }
                Column(
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(formatSpendAmount(selected.amount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(selected.detailLabel, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                }
                IconButton(onClick = { selectedIndex++ }, enabled = selectedIndex < points.lastIndex) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Next period")
                }
            }
            Text(
                "Tap a bar to see its recorded spend",
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
