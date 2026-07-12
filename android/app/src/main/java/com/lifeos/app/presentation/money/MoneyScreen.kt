package com.lifeos.app.presentation.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.theme.LifeOsColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Whole rupees with Indian (lakh/crore) digit grouping: ₹12,34,567. */
private fun BigDecimal.rupees(): String {
    val plain = this.setScale(0, RoundingMode.HALF_UP).toPlainString()
    val negative = plain.startsWith("-")
    val digits = if (negative) plain.drop(1) else plain
    val grouped = if (digits.length <= 3) digits else {
        val tail = digits.takeLast(3)
        val head = digits.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
    }
    return (if (negative) "-₹" else "₹") + grouped
}

/** One category's identity color — distinct hues, desaturated for the warm
 * ivory canvas, consistent everywhere. */
private fun categoryColor(category: String): Color = when (category.lowercase()) {
    "fuel" -> Color(0xFFC76E3B)          // burnt orange
    "food" -> Color(0xFFC24E4E)          // clay red
    "groceries" -> Color(0xFF6B9B5E)     // moss
    "shopping" -> Color(0xFF5B7DB1)      // dusk blue
    "subscriptions" -> Color(0xFF8A6FB8) // muted violet
    "investments" -> Color(0xFF5F6FAE)   // slate indigo
    "entertainment" -> Color(0xFFC05F86) // rose clay
    "legal_fees" -> Color(0xFF8A8578)    // warm stone
    "emi_fees" -> Color(0xFFC99A3C)      // ochre
    "wallet_load" -> Color(0xFF5FA396)   // sea teal
    "travel" -> Color(0xFF58A3B4)        // lagoon
    else -> LifeOsColors.OnSurfaceMuted
}

private fun prettyCategory(category: String): String =
    category.replace('_', ' ').replaceFirstChar { it.uppercase() }

// --- period math ---------------------------------------------------------------

private data class PeriodSlice(val start: LocalDate, val end: LocalDate)

private fun sliceFor(
    period: SpendPeriod,
    anchor: LocalDate,
    customStart: LocalDate?,
    customEnd: LocalDate?,
): PeriodSlice = when (period) {
    SpendPeriod.DAY -> PeriodSlice(anchor, anchor)
    SpendPeriod.WEEK -> {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong()) // Monday
        PeriodSlice(start, start.plusDays(6))
    }
    SpendPeriod.MONTH -> {
        val start = anchor.withDayOfMonth(1)
        PeriodSlice(start, anchor.withDayOfMonth(anchor.lengthOfMonth()))
    }
    SpendPeriod.SIX_MONTHS -> {
        // Six calendar months ending with the anchor's month.
        val start = anchor.withDayOfMonth(1).minusMonths(5)
        PeriodSlice(start, anchor.withDayOfMonth(anchor.lengthOfMonth()))
    }
    SpendPeriod.YEAR -> PeriodSlice(
        LocalDate.of(anchor.year, 1, 1),
        LocalDate.of(anchor.year, 12, 31),
    )
    SpendPeriod.CUSTOM -> {
        // Fall back to the anchor month until a range is picked.
        val s = customStart ?: anchor.withDayOfMonth(1)
        val e = customEnd ?: anchor.withDayOfMonth(anchor.lengthOfMonth())
        PeriodSlice(s, e)
    }
}

private fun periodLabel(period: SpendPeriod, anchor: LocalDate, slice: PeriodSlice): String {
    val today = LocalDate.now()
    val dm = DateTimeFormatter.ofPattern("dd MMM")
    val dmy = DateTimeFormatter.ofPattern("dd MMM yy")
    val my = DateTimeFormatter.ofPattern("MMM yyyy")
    return when (period) {
        SpendPeriod.DAY -> when (anchor) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> anchor.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))
        }
        SpendPeriod.WEEK ->
            if (!today.isBefore(slice.start) && !today.isAfter(slice.end)) "This week"
            else "${slice.start.format(dm)} – ${slice.end.format(dm)}"
        SpendPeriod.MONTH ->
            if (anchor.year == today.year && anchor.month == today.month) "This month"
            else anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        SpendPeriod.SIX_MONTHS ->
            if (anchor.year == today.year && anchor.month == today.month) "Last 6 months"
            else "${slice.start.format(my)} – ${slice.end.format(my)}"
        SpendPeriod.YEAR ->
            if (anchor.year == today.year) "This year" else anchor.year.toString()
        SpendPeriod.CUSTOM ->
            "${slice.start.format(dmy)} – ${slice.end.format(dmy)}"
    }
}

private data class CategoryTotal(val category: String, val total: BigDecimal, val count: Int)

@Composable
fun MoneyScreen(viewModel: MoneyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRangePicker by remember { mutableStateOf(false) }

    val slice = remember(state.period, state.anchor, state.customStart, state.customEnd) {
        sliceFor(state.period, state.anchor, state.customStart, state.customEnd)
    }
    // Everything spendable inside the period, before the category filter —
    // this is what the filter chips are built from, so they never vanish.
    val sliceTxns = remember(state.transactions, slice) {
        state.transactions.filter {
            it.direction == "debit" && it.spendCategory != null &&
                !it.txnDate.isBefore(slice.start) && !it.txnDate.isAfter(slice.end)
        }
    }
    val availableCategories = remember(sliceTxns) {
        sliceTxns.groupBy { it.spendCategory!! }
            .map { (cat, list) -> cat to list.fold(BigDecimal.ZERO) { a, t -> a + t.amount } }
            .sortedByDescending { it.second }
            .map { it.first }
    }
    val filter = state.categoryFilter
    val spendTxns = remember(sliceTxns, filter) {
        if (filter.isEmpty()) sliceTxns else sliceTxns.filter { it.spendCategory in filter }
    }
    val total = remember(spendTxns) {
        spendTxns.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
    }
    val byCategory = remember(spendTxns) {
        spendTxns.groupBy { it.spendCategory!! }
            .map { (cat, list) -> CategoryTotal(cat, list.fold(BigDecimal.ZERO) { a, t -> a + t.amount }, list.size) }
            .sortedByDescending { it.total }
    }
    val canGoNext = state.period != SpendPeriod.CUSTOM && slice.end.isBefore(LocalDate.now())
    val canGoPrev = state.period != SpendPeriod.CUSTOM
    val selected = state.selectedCategory
    val drillDownRows = remember(spendTxns, selected) {
        if (selected == null) emptyList()
        else spendTxns.filter { it.spendCategory == selected }.sortedByDescending { it.txnDate }
    }

    if (showRangePicker) {
        CustomRangeDialog(
            initialStart = state.customStart,
            initialEnd = state.customEnd,
            onDismiss = { showRangePicker = false },
            onConfirm = { s, e ->
                showRangePicker = false
                viewModel.setCustomRange(s, e)
            },
        )
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

        item {
            PeriodToggle(
                selected = state.period,
                onSelect = { period ->
                    if (period == SpendPeriod.CUSTOM) showRangePicker = true
                    else viewModel.setPeriod(period)
                },
            )
        }

        item {
            SpendSummaryCard(
                label = periodLabel(state.period, state.anchor, slice),
                total = total,
                txnCount = spendTxns.size,
                filtered = filter.isNotEmpty(),
                canGoPrev = canGoPrev,
                canGoNext = canGoNext,
                onPrev = viewModel::prevPeriod,
                onNext = viewModel::nextPeriod,
                onLabelTap = if (state.period == SpendPeriod.CUSTOM) {
                    { showRangePicker = true }
                } else null,
            )
        }

        if (availableCategories.isNotEmpty()) {
            item {
                CategoryFilterRow(
                    categories = availableCategories,
                    selected = filter,
                    onToggle = viewModel::toggleCategoryFilter,
                    onClear = viewModel::clearCategoryFilter,
                )
            }
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
            // Drill-down: one card, dues-breakdown style — header on top,
            // this category's transactions listed right below it.
            val catTotal = byCategory.firstOrNull { it.category == selected }?.total ?: BigDecimal.ZERO
            item {
                DrillDownCard(
                    category = selected,
                    total = catTotal,
                    txns = drillDownRows,
                    onBack = { viewModel.selectCategory(null) },
                )
            }
        }

        // Demoted: card dues live at the very bottom, compact, never the headline.
        state.totalDue?.takeIf { it > BigDecimal.ZERO }?.let { due ->
            item { DuesFooterCard(due, state.nextDueDate, state.cardDues) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PeriodToggle(selected: SpendPeriod, onSelect: (SpendPeriod) -> Unit) {
    val options = listOf(
        SpendPeriod.DAY to "Day",
        SpendPeriod.WEEK to "Week",
        SpendPeriod.MONTH to "Month",
        SpendPeriod.SIX_MONTHS to "6M",
        SpendPeriod.YEAR to "Year",
        SpendPeriod.CUSTOM to "Custom",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LifeOsColors.SurfaceMuted)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (period, label) ->
            val active = period == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) LifeOsColors.Surface else Color.Transparent)
                    .clickable { onSelect(period) }
                    .padding(vertical = 8.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) LifeOsColors.Primary else LifeOsColors.OnSurfaceMuted,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = initialEnd?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                onClick = {
                    val s = Instant.ofEpochMilli(pickerState.selectedStartDateMillis!!)
                        .atZone(ZoneOffset.UTC).toLocalDate()
                    val e = Instant.ofEpochMilli(pickerState.selectedEndDateMillis!!)
                        .atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(s, e)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CategoryFilterRow(
    categories: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            label = "All",
            active = selected.isEmpty(),
            color = LifeOsColors.Primary,
            showDot = false,
            onClick = onClear,
        )
        categories.forEach { cat ->
            FilterChip(
                label = prettyCategory(cat),
                active = cat in selected,
                color = categoryColor(cat),
                showDot = true,
                onClick = { onToggle(cat) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    color: Color,
    showDot: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) color.copy(alpha = 0.12f) else LifeOsColors.Surface)
            .border(
                width = 1.dp,
                color = if (active) color.copy(alpha = 0.45f) else LifeOsColors.SurfaceBorder,
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (showDot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) color else LifeOsColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun SpendSummaryCard(
    label: String,
    total: BigDecimal,
    txnCount: Int,
    filtered: Boolean,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLabelTap: (() -> Unit)?,
) {
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Primary) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onPrev, enabled = canGoPrev, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.ChevronLeft,
                        contentDescription = "Previous",
                        tint = if (canGoPrev) LifeOsColors.OnSurfaceMuted else LifeOsColors.SurfaceBorder,
                    )
                }
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (onLabelTap != null) LifeOsColors.Primary else LifeOsColors.OnSurfaceMuted,
                    modifier = Modifier
                        .weight(1f)
                        .let { m -> if (onLabelTap != null) m.clickable(onClick = onLabelTap) else m },
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
                when {
                    txnCount == 0 -> "No spend recorded"
                    filtered -> "across $txnCount transaction${if (txnCount == 1) "" else "s"} (filtered)"
                    else -> "across $txnCount transaction${if (txnCount == 1) "" else "s"}"
                },
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
                    val share = if (total > BigDecimal.ZERO) {
                        (c.total.toDouble() / total.toDouble() * 100).let { p ->
                            if (p < 1.0) "<1%" else "${p.toInt()}%"
                        }
                    } else null
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            c.total.rupees(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = LifeOsColors.OnBackground,
                        )
                        Text(
                            listOfNotNull("${c.count}", share).joinToString(" · "),
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

private enum class TxnSortKey { DATE, AMOUNT }

/** Drill-down in the same style as the card-dues breakdown: a single card
 * with a summary row on top and plain stacked rows beneath it. Rows can be
 * sorted by date (recent/oldest) or amount (high/low). */
@Composable
private fun DrillDownCard(
    category: String,
    total: BigDecimal,
    txns: List<Transaction>,
    onBack: () -> Unit,
) {
    var sortKey by remember { mutableStateOf(TxnSortKey.DATE) }
    var descending by remember { mutableStateOf(true) }
    val sorted = remember(txns, sortKey, descending) {
        val base = when (sortKey) {
            TxnSortKey.DATE -> txns.sortedBy { it.txnDate }
            TxnSortKey.AMOUNT -> txns.sortedBy { it.amount }
        }
        if (descending) base.reversed() else base
    }
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = categoryColor(category)) {
        Column {
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
                        "${txns.size} transaction${if (txns.size == 1) "" else "s"}",
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
            if (txns.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortChip(
                        label = if (sortKey == TxnSortKey.DATE && !descending) "Oldest" else "Recent",
                        active = sortKey == TxnSortKey.DATE,
                        descending = descending,
                        onClick = {
                            if (sortKey == TxnSortKey.DATE) descending = !descending
                            else { sortKey = TxnSortKey.DATE; descending = true }
                        },
                    )
                    SortChip(
                        label = "Amount",
                        active = sortKey == TxnSortKey.AMOUNT,
                        descending = descending,
                        onClick = {
                            if (sortKey == TxnSortKey.AMOUNT) descending = !descending
                            else { sortKey = TxnSortKey.AMOUNT; descending = true }
                        },
                    )
                }
            }
            if (sorted.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sorted.forEach { txn -> TransactionRow(txn) }
                }
            }
        }
    }
}

/** Small sort pill; the active one shows direction and taps toggle it. */
@Composable
private fun SortChip(
    label: String,
    active: Boolean,
    descending: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = if (active) "$label ${if (descending) "↓" else "↑"}" else label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) LifeOsColors.Primary else LifeOsColors.OnSurfaceMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) LifeOsColors.PrimarySoft else LifeOsColors.SurfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun TransactionRow(txn: Transaction) {
    val isCredit = txn.direction == "credit"
    val amountColor = if (isCredit) LifeOsColors.Positive else LifeOsColors.OnBackground
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                txn.counterparty?.takeIf { it.isNotBlank() } ?: txn.source,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = LifeOsColors.OnBackground,
                maxLines = 1,
            )
            val datePart = txn.txnDate.format(DateTimeFormatter.ofPattern("dd MMM"))
            // Show the time only when we actually know it; otherwise stay
            // date-only rather than render a placeholder.
            val timePart = txn.txnAt
                ?.atZone(ZoneId.systemDefault())
                ?.format(DateTimeFormatter.ofPattern("h:mm a"))
            val whenText = if (timePart != null) "$datePart, $timePart" else datePart
            Text(
                "$whenText · ${txn.account ?: txn.source}",
                style = MaterialTheme.typography.labelSmall,
                color = LifeOsColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            (if (isCredit) "+" else "") + txn.amount.rupees(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
    }
}

@Composable
private fun DuesFooterCard(
    totalDue: BigDecimal,
    nextDueDate: LocalDate?,
    cards: List<CardStatement>,
) {
    var expanded by remember { mutableStateOf(false) }
    val expandable = cards.isNotEmpty()
    val dm = DateTimeFormatter.ofPattern("dd MMM")
    LifeOsCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { m -> if (expandable) m.clickable { expanded = !expanded } else m },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Upcoming card dues",
                        style = MaterialTheme.typography.labelMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                    if (nextDueDate != null) {
                        Text(
                            "Next: ${nextDueDate.format(dm)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        totalDue.rupees(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                    if (expandable) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (expanded) "Hide per-card dues" else "Show per-card dues",
                            tint = LifeOsColors.OnSurfaceMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.forEach { card ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val dueSoon = card.dueDate?.let {
                                !it.isBefore(LocalDate.now()) && !it.isAfter(LocalDate.now().plusDays(7))
                            } ?: false
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    card.cardLabel + (card.cardLast4?.let { " ····$it" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = LifeOsColors.OnBackground,
                                    maxLines = 1,
                                )
                                Text(
                                    listOfNotNull(
                                        card.dueDate?.let { "Due ${it.format(dm)}" },
                                        card.bank,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (dueSoon) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
                                    fontWeight = if (dueSoon) FontWeight.SemiBold else null,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                (card.totalDue ?: BigDecimal.ZERO).rupees(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = LifeOsColors.OnBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}
