package com.lifeos.app.presentation.money

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.theme.LifeOsColors
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun BigDecimal.rupees(): String = "₹" + this.toPlainString().let { s ->
    if (s.endsWith(".00")) s.dropLast(3) else s
}

/** One category's identity color — distinct hues, consistent everywhere. */
private fun categoryColor(category: String): Color = when (category.lowercase()) {
    "fuel" -> Color(0xFFF97316)
    "food" -> Color(0xFFEF4444)
    "groceries" -> Color(0xFF22C55E)
    "shopping" -> Color(0xFF3B82F6)
    "subscriptions" -> Color(0xFF8B5CF6)
    "investments" -> Color(0xFF6366F1)
    "entertainment" -> Color(0xFFEC4899)
    "legal_fees" -> Color(0xFF64748B)
    "emi_fees" -> Color(0xFFF59E0B)
    "wallet_load" -> Color(0xFF14B8A6)
    "travel" -> Color(0xFF06B6D4)
    else -> LifeOsColors.OnSurfaceMuted
}

private fun prettyCategory(category: String): String =
    category.replace('_', ' ').replaceFirstChar { it.uppercase() }

// --- period math ---------------------------------------------------------------

private data class PeriodSlice(val start: LocalDate, val end: LocalDate)

private fun sliceFor(period: SpendPeriod, anchor: LocalDate): PeriodSlice = when (period) {
    SpendPeriod.DAY -> PeriodSlice(anchor, anchor)
    SpendPeriod.WEEK -> {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong()) // Monday
        PeriodSlice(start, start.plusDays(6))
    }
    SpendPeriod.MONTH -> {
        val start = anchor.withDayOfMonth(1)
        PeriodSlice(start, anchor.withDayOfMonth(anchor.lengthOfMonth()))
    }
}

private fun periodLabel(period: SpendPeriod, anchor: LocalDate): String {
    val today = LocalDate.now()
    val dm = DateTimeFormatter.ofPattern("dd MMM")
    return when (period) {
        SpendPeriod.DAY -> when (anchor) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> anchor.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))
        }
        SpendPeriod.WEEK -> {
            val s = sliceFor(period, anchor)
            if (!today.isBefore(s.start) && !today.isAfter(s.end)) "This week"
            else "${s.start.format(dm)} – ${s.end.format(dm)}"
        }
        SpendPeriod.MONTH ->
            if (anchor.year == today.year && anchor.month == today.month) "This month"
            else anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    }
}

private data class CategoryTotal(val category: String, val total: BigDecimal, val count: Int)

@Composable
fun MoneyScreen(viewModel: MoneyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val slice = remember(state.period, state.anchor) { sliceFor(state.period, state.anchor) }
    val spendTxns = remember(state.transactions, slice) {
        state.transactions.filter {
            it.direction == "debit" && it.spendCategory != null &&
                !it.txnDate.isBefore(slice.start) && !it.txnDate.isAfter(slice.end)
        }
    }
    val total = remember(spendTxns) {
        spendTxns.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
    }
    val byCategory = remember(spendTxns) {
        spendTxns.groupBy { it.spendCategory!! }
            .map { (cat, list) -> CategoryTotal(cat, list.fold(BigDecimal.ZERO) { a, t -> a + t.amount }, list.size) }
            .sortedByDescending { it.total }
    }
    val canGoNext = slice.end.isBefore(LocalDate.now())
    val selected = state.selectedCategory
    val drillDownRows = remember(spendTxns, selected) {
        if (selected == null) emptyList()
        else spendTxns.filter { it.spendCategory == selected }.sortedByDescending { it.txnDate }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    ScreenHeader("Money", "Where it's going.")
                }
                IconButton(onClick = viewModel::syncNow, enabled = !state.syncing) {
                    if (state.syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Sync now", tint = LifeOsColors.Primary)
                    }
                }
            }
        }

        state.error?.let { err ->
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Warning) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Couldn't refresh", style = MaterialTheme.typography.titleSmall, color = LifeOsColors.Warning)
                        Text(
                            "$err — showing the last synced data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            }
        }

        item { PeriodToggle(state.period, onSelect = viewModel::setPeriod) }

        item {
            SpendSummaryCard(
                label = periodLabel(state.period, state.anchor),
                total = total,
                txnCount = spendTxns.size,
                canGoNext = canGoNext,
                onPrev = viewModel::prevPeriod,
                onNext = viewModel::nextPeriod,
            )
        }

        if (state.loading && state.transactions.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (selected == null) {
            // Category breakdown for the period; tap a row to drill in.
            if (byCategory.isEmpty()) {
                item { EmptyPeriodCard() }
            } else {
                item {
                    CategoryBreakdownCard(byCategory, total, onSelect = viewModel::selectCategory)
                }
            }
        } else {
            // Drill-down: this category's transactions within the period.
            val catTotal = byCategory.firstOrNull { it.category == selected }?.total ?: BigDecimal.ZERO
            item { DrillDownHeader(selected, catTotal, drillDownRows.size, onBack = { viewModel.selectCategory(null) }) }
            items(drillDownRows, key = { it.id }) { txn -> TransactionRow(txn) }
        }

        // Demoted: card dues live at the very bottom, compact, never the headline.
        state.totalDue?.takeIf { it > BigDecimal.ZERO }?.let { due ->
            item { DuesFooterCard(due, state.nextDueDate) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PeriodToggle(selected: SpendPeriod, onSelect: (SpendPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LifeOsColors.SurfaceMuted)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SpendPeriod.values().forEach { period ->
            val active = period == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) LifeOsColors.Surface else Color.Transparent)
                    .clickable { onSelect(period) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (period) {
                        SpendPeriod.DAY -> "Day"
                        SpendPeriod.WEEK -> "Week"
                        SpendPeriod.MONTH -> "Month"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) LifeOsColors.Primary else LifeOsColors.OnSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun SpendSummaryCard(
    label: String,
    total: BigDecimal,
    txnCount: Int,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Primary) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous", tint = LifeOsColors.OnSurfaceMuted)
                }
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = "Next",
                        tint = if (canGoNext) LifeOsColors.OnSurfaceMuted else LifeOsColors.SurfaceBorder,
                    )
                }
            }
            Text(
                total.rupees(),
                style = MaterialTheme.typography.displaySmall,
                color = LifeOsColors.OnBackground,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (txnCount == 0) "No spend recorded" else "across $txnCount transaction${if (txnCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = LifeOsColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun EmptyPeriodCard() {
    LifeOsCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Nothing here for this period. Use ‹ › above to look at another one, or tap sync to pull the latest.",
            style = MaterialTheme.typography.bodyMedium,
            color = LifeOsColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun CategoryBreakdownCard(
    categories: List<CategoryTotal>,
    total: BigDecimal,
    onSelect: (String) -> Unit,
) {
    val maxTotal = categories.maxOfOrNull { it.total.toDouble() }?.coerceAtLeast(1.0) ?: 1.0
    LifeOsCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            categories.forEach { c ->
                val color = categoryColor(c.category)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(c.category) }
                        .padding(vertical = 8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            prettyCategory(c.category),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LifeOsColors.OnBackground,
                            fontWeight = FontWeight.Medium,
                        )
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(LifeOsColors.SurfaceMuted),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((c.total.toDouble() / maxTotal).toFloat().coerceIn(0.03f, 1f))
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(color),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            c.total.rupees(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = LifeOsColors.OnBackground,
                        )
                        Text(
                            "${c.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = "See ${prettyCategory(c.category)} transactions",
                        tint = LifeOsColors.OnSurfaceMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrillDownHeader(category: String, total: BigDecimal, count: Int, onBack: () -> Unit) {
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = categoryColor(category)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to categories", tint = LifeOsColors.Primary)
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    prettyCategory(category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LifeOsColors.OnBackground,
                )
                Text(
                    "$count transaction${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            Text(
                total.rupees(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = LifeOsColors.OnBackground,
            )
        }
    }
}

@Composable
private fun TransactionRow(txn: Transaction) {
    val isCredit = txn.direction == "credit"
    val amountColor = if (isCredit) LifeOsColors.Positive else LifeOsColors.OnBackground
    LifeOsCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    txn.counterparty?.takeIf { it.isNotBlank() } ?: txn.source,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = LifeOsColors.OnBackground,
                    maxLines = 1,
                )
                Text(
                    "${txn.txnDate.format(DateTimeFormatter.ofPattern("dd MMM"))} · ${txn.account ?: txn.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            Text(
                (if (isCredit) "+" else "") + txn.amount.rupees(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = amountColor,
            )
        }
    }
}

@Composable
private fun DuesFooterCard(totalDue: BigDecimal, nextDueDate: LocalDate?) {
    LifeOsCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    "Upcoming card dues",
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                if (nextDueDate != null) {
                    Text(
                        "Next: ${nextDueDate.format(DateTimeFormatter.ofPattern("dd MMM"))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
            Text(
                totalDue.rupees(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = LifeOsColors.OnSurfaceMuted,
            )
        }
    }
}
