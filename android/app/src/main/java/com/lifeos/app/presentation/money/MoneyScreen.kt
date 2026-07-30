package com.lifeos.app.presentation.money

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.RepaymentCandidate
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

private val CURRENCY_SYMBOLS = mapOf("INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£")

/** Amount rendered in its own currency. INR keeps the Indian lakh/crore
 * grouping (₹12,34,567); every other currency uses plain thousands grouping
 * with its symbol where we know one, else the ISO code as a suffix ("23 AED")
 * so a foreign-currency alert never gets mislabeled as rupees. */
private fun BigDecimal.money(currency: String): String {
    if (currency == "INR") return rupees()
    val plain = this.setScale(0, RoundingMode.HALF_UP).toPlainString()
    val negative = plain.startsWith("-")
    val digits = if (negative) plain.drop(1) else plain
    val grouped = if (digits.length <= 3) digits
        else digits.reversed().chunked(3).joinToString(",").reversed()
    val sign = if (negative) "-" else ""
    val symbol = CURRENCY_SYMBOLS[currency]
    return if (symbol != null) "$sign$symbol$grouped" else "$sign$grouped $currency"
}

/** One category's identity color: distinct hues, desaturated for the warm
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
    "emi" -> Color(0xFFD07B43)           // warm copper
    "emi_fees" -> Color(0xFFC99A3C)      // ochre
    "wallet_load" -> Color(0xFF5FA396)   // sea teal
    "travel" -> Color(0xFF58A3B4)        // lagoon
    else -> LifeOsColors.OnSurfaceMuted
}

private fun prettyCategory(category: String): String = when (category.lowercase()) {
    "emi" -> "EMI"
    "emi_fees" -> "EMI fees"
    else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun compactCardLabel(label: String, last4: String): String {
    val compact = label.replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+credit card$", RegexOption.IGNORE_CASE), "")
        .trim()
    return "$compact ····$last4"
}

private val EDITABLE_CATEGORIES = listOf(
    "internet", "utilities", "food", "groceries", "fuel", "travel", "shopping",
    "subscriptions", "entertainment", "medical", "education", "rent", "emi",
    "investments", "legal_fees", "other",
)

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
            else "${slice.start.format(dm)} to ${slice.end.format(dm)}"
        SpendPeriod.MONTH ->
            if (anchor.year == today.year && anchor.month == today.month) "This month"
            else anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        SpendPeriod.SIX_MONTHS ->
            if (anchor.year == today.year && anchor.month == today.month) "Last 6 months"
            else "${slice.start.format(my)} to ${slice.end.format(my)}"
        SpendPeriod.YEAR ->
            if (anchor.year == today.year) "This year" else anchor.year.toString()
        SpendPeriod.CUSTOM ->
            "${slice.start.format(dmy)} to ${slice.end.format(dmy)}"
    }
}

private data class CategoryTotal(val category: String, val total: BigDecimal, val count: Int)
private enum class CardCycleView { BILLED, CURRENT }

@Composable
fun MoneyScreen(
    onOpenStatement: (Int) -> Unit = {},
    onOpenCurrentCycle: (String, String) -> Unit = { _, _ -> },
    viewModel: MoneyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRangePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val slice = remember(state.period, state.anchor, state.customStart, state.customEnd) {
        sliceFor(state.period, state.anchor, state.customStart, state.customEnd)
    }
    // Everything spendable inside the period, before the category filter.
    // this is what the filter chips are built from, so they never vanish.
    val sliceTxns = remember(state.transactions, slice) {
        state.transactions.filter {
            it.direction == "debit" && it.spendCategory != null &&
                !it.txnDate.isBefore(slice.start) && !it.txnDate.isAfter(slice.end)
        }
    }
    val bankUpiAccounts = remember(state.transactions) {
        bankUpiAccountOptions(state.transactions)
    }
    val bankUpiKeys = remember(bankUpiAccounts) { bankUpiAccounts.map { it.key }.toSet() }
    val sourceFilteredTxns = remember(sliceTxns, state.bankUpiFilter, bankUpiKeys) {
        when (val sourceFilter = state.bankUpiFilter) {
            null -> sliceTxns
            ALL_BANK_UPI_FILTER -> sliceTxns.filter {
                it.bankUpiAccountKey(bankUpiKeys) != null
            }
            else -> sliceTxns.filter {
                it.bankUpiAccountKey(bankUpiKeys) == sourceFilter
            }
        }
    }
    val availableCategories = remember(sourceFilteredTxns) {
        sourceFilteredTxns.groupBy { it.spendCategory!! }
            .map { (cat, list) -> cat to list.fold(BigDecimal.ZERO) { a, t -> a + t.amount } }
            .sortedByDescending { it.second }
            .map { it.first }
    }
    val filter = state.categoryFilter
    val spendTxns = remember(sourceFilteredTxns, filter) {
        if (filter.isEmpty()) {
            sourceFilteredTxns
        } else {
            sourceFilteredTxns.filter { it.spendCategory in filter }
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

    state.categoryEditTransaction?.let { transaction ->
        CategoryEditDialog(
            transaction = transaction,
            saving = state.categorySaving,
            error = state.categoryError,
            onSave = viewModel::saveCategory,
            onDismiss = viewModel::dismissCategoryEdit,
        )
    }

    state.repaymentDebit?.let { debit ->
        RepaymentDialog(
            debit = debit,
            candidates = state.repaymentCandidates,
            selectedCreditId = state.selectedRepaymentCreditId,
            loading = state.repaymentLoading,
            saving = state.repaymentSaving,
            error = state.repaymentError,
            onSelectCredit = viewModel::selectRepaymentCredit,
            onConfirm = viewModel::confirmRepayment,
            onDismiss = viewModel::dismissRepayment,
        )
    }

    LaunchedEffect(state.completedRepayment?.id) {
        val repayment = state.completedRepayment ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Repayment saved. Both transactions are excluded from totals.",
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoRepayment(repayment.id)
        }
        viewModel.consumeCompletedRepayment()
    }

    Box(modifier = Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        item { ScreenHeader("Money", "Where it's going.") }

        state.error?.let { err ->
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Warning) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Couldn't refresh", style = MaterialTheme.typography.titleSmall, color = LifeOsColors.Warning)
                        Text(
                            "$err. Showing the last synced data.",
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
                filtered = filter.isNotEmpty() || state.bankUpiFilter != null,
                bankUpiFilter = state.bankUpiFilter,
                bankUpiAccounts = bankUpiAccounts,
                onBankUpiFilterChange = viewModel::setBankUpiFilter,
                canGoPrev = canGoPrev,
                canGoNext = canGoNext,
                onPrev = viewModel::prevPeriod,
                onNext = viewModel::nextPeriod,
                onLabelTap = if (state.period == SpendPeriod.CUSTOM) {
                    { showRangePicker = true }
                } else null,
            )
        }

        if (state.cardDues.isNotEmpty() || state.currentCardCycles.isNotEmpty()) {
            item {
                CreditCardCyclesCard(
                    confirmedTotal = state.totalDue ?: BigDecimal.ZERO,
                    confirmedCards = state.cardDues,
                    currentCycles = state.currentCardCycles,
                    onOpenDetails = onOpenStatement,
                    onOpenCurrentCycle = onOpenCurrentCycle,
                )
            }
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
            // Drill-down: one card, dues-breakdown style, with the header on top.
            // this category's transactions listed right below it.
            val catTotal = byCategory.firstOrNull { it.category == selected }?.total ?: BigDecimal.ZERO
            item {
                DrillDownCard(
                    category = selected,
                    total = catTotal,
                    txns = drillDownRows,
                    onBack = { viewModel.selectCategory(null) },
                    onMarkAsRepaid = viewModel::beginRepayment,
                    onEditCategory = viewModel::beginCategoryEdit,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
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
    bankUpiFilter: String?,
    bankUpiAccounts: List<BankUpiAccountOption>,
    onBankUpiFilterChange: (String?) -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        txnCount == 0 -> "No spend recorded"
                        filtered -> "$txnCount transaction${if (txnCount == 1) "" else "s"} · filtered"
                        else -> "$txnCount transaction${if (txnCount == 1) "" else "s"}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                BankUpiFilterMenu(
                    selected = bankUpiFilter,
                    accounts = bankUpiAccounts,
                    onSelect = onBankUpiFilterChange,
                )
            }
        }
    }
}

@Composable
private fun BankUpiFilterMenu(
    selected: String?,
    accounts: List<BankUpiAccountOption>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when (selected) {
        null -> "All spend"
        ALL_BANK_UPI_FILTER -> "Bank UPI · All"
        else -> accounts.firstOrNull { it.key == selected }?.let { "UPI · ${it.label}" }
            ?: "Bank UPI"
    }

    Box {
        Surface(
            modifier = Modifier.clickable { expanded = true },
            shape = RoundedCornerShape(50),
            color = if (selected == null) LifeOsColors.SurfaceMuted else LifeOsColors.PrimarySoft,
        ) {
            Text(
                "$selectedLabel ▾",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected == null) LifeOsColors.OnSurfaceMuted else LifeOsColors.Primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All spend") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            DropdownMenuItem(
                text = { Text("Bank UPI · All banks") },
                onClick = {
                    expanded = false
                    onSelect(ALL_BANK_UPI_FILTER)
                },
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("Bank UPI · ${account.label}") },
                    onClick = {
                        expanded = false
                        onSelect(account.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyPeriodCard() {
    LifeOsCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Nothing here for this period. Use ‹ › above to look at another one, or sync LifeOS from Settings.",
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
    onMarkAsRepaid: (Transaction) -> Unit,
    onEditCategory: (Transaction) -> Unit,
) {
    var sortKey by remember { mutableStateOf(TxnSortKey.DATE) }
    var descending by remember { mutableStateOf(true) }
    var selectedTxn by remember { mutableStateOf<Transaction?>(null) }
    val sorted = remember(txns, sortKey, descending) {
        val base = when (sortKey) {
            TxnSortKey.DATE -> txns.sortedBy { it.txnDate }
            TxnSortKey.AMOUNT -> txns.sortedBy { it.amount }
        }
        if (descending) base.reversed() else base
    }
    selectedTxn?.let { txn ->
        TransactionDetailDialog(
            transaction = txn,
            onDismiss = { selectedTxn = null },
            onEditCategory = {
                selectedTxn = null
                onEditCategory(txn)
            },
            onMarkAsRepaid = {
                selectedTxn = null
                onMarkAsRepaid(txn)
            },
        )
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
                    sorted.forEach { txn ->
                        TransactionRow(txn, onClick = { selectedTxn = txn })
                    }
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
private fun TransactionRow(txn: Transaction, onClick: (() -> Unit)? = null) {
    val isCredit = txn.direction == "credit"
    val amountColor = if (isCredit) LifeOsColors.Positive else LifeOsColors.OnBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 2.dp),
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
            (if (isCredit) "+" else "") + txn.amount.money(txn.currency),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "Transaction details",
                tint = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TransactionDetailDialog(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onEditCategory: () -> Unit,
    onMarkAsRepaid: () -> Unit,
) {
    val whenText = transaction.txnAt
        ?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a"))
        ?: transaction.txnDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    transaction.counterparty?.takeIf { it.isNotBlank() } ?: transaction.source,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    transaction.amount.money(transaction.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    color = LifeOsColors.OnBackground,
                )
                Text(
                    "$whenText · ${transaction.account ?: transaction.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Text(
                    "Category: ${prettyCategory(transaction.spendCategory ?: "excluded")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Text(
                    "If this money was returned, pair it with the incoming payment. The bank records will be kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onEditCategory) { Text("Edit category") }
                if (transaction.direction == "debit") {
                    TextButton(onClick = onMarkAsRepaid) { Text("Mark as repaid") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CategoryEditDialog(
    transaction: Transaction,
    saving: Boolean,
    error: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(transaction.id) { mutableStateOf(transaction.spendCategory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose category") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    transaction.counterparty?.takeIf { it.isNotBlank() } ?: transaction.source,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "This changes only this transaction and saves it on your Jetson.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Spacer(Modifier.height(6.dp))
                listOf<String?>(null).plus(EDITABLE_CATEGORIES).forEach { category ->
                    val isSelected = selected == category
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !saving) { selected = category }
                            .padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selected = category },
                            enabled = !saving,
                        )
                        Text(category?.let(::prettyCategory) ?: "Automatic")
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }, enabled = !saving) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}

@Composable
private fun RepaymentDialog(
    debit: Transaction,
    candidates: List<RepaymentCandidate>,
    selectedCreditId: Int?,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    onSelectCredit: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the returned payment") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Sent ${debit.amount.money(debit.currency)} to " +
                        (debit.counterparty?.takeIf { it.isNotBlank() } ?: "this person"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Select the credit that returned this money. Both records will remain in the database but will stop affecting totals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                    candidates.isEmpty() -> Text(
                        "No matching incoming payment was found. Sync LifeOS in Settings and try again after the return is received.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                    else -> candidates.forEachIndexed { index, candidate ->
                        val credit = candidate.transaction
                        val selected = credit.id == selectedCreditId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) LifeOsColors.PrimarySoft else LifeOsColors.SurfaceMuted
                                )
                                .border(
                                    1.dp,
                                    if (selected) LifeOsColors.Primary else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onSelectCredit(credit.id) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelectCredit(credit.id) },
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    credit.counterparty?.takeIf { it.isNotBlank() } ?: credit.source,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Text(
                                    (if (index == 0 && candidate.matchScore >= 85) "Likely match · " else "") +
                                        candidate.reason + " · " +
                                        credit.txnDate.format(DateTimeFormatter.ofPattern("dd MMM")),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LifeOsColors.OnSurfaceMuted,
                                )
                            }
                            Text(
                                "+" + credit.amount.money(credit.currency),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = LifeOsColors.Positive,
                            )
                        }
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning)
                }
            }
        },
        confirmButton = {
            HoldToConfirmButton(
                enabled = selectedCreditId != null && !loading,
                saving = saving,
                onConfirm = onConfirm,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}

@Composable
private fun HoldToConfirmButton(
    enabled: Boolean,
    saving: Boolean,
    onConfirm: () -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(holding, enabled, saving) {
        if (holding && enabled && !saving) {
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = tween(1_500, easing = LinearEasing))
            if (holding) {
                holding = false
                onConfirm()
            }
        } else {
            progress.snapTo(0f)
        }
    }

    val canConfirm = enabled && !saving
    Surface(
        modifier = Modifier
            .width(190.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                onLongClick(label = "Confirm repayment") {
                    if (canConfirm) onConfirm()
                    canConfirm
                }
            }
            .pointerInput(canConfirm) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (canConfirm) holding = true
                    waitForUpOrCancellation()
                    holding = false
                }
            },
        color = if (canConfirm) LifeOsColors.Primary else LifeOsColors.SurfaceMuted,
        contentColor = if (canConfirm) Color.White else LifeOsColors.OnSurfaceMuted,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (holding) {
                LinearProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White.copy(alpha = 0.28f),
                    trackColor = Color.Transparent,
                )
            }
            Text(
                if (saving) "Saving…" else if (holding) "Keep holding…" else "Hold to confirm",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CreditCardCyclesCard(
    confirmedTotal: BigDecimal,
    confirmedCards: List<CardStatement>,
    currentCycles: List<CurrentCardCycle>,
    onOpenDetails: (Int) -> Unit,
    onOpenCurrentCycle: (String, String) -> Unit,
) {
    var selectedView by remember { mutableStateOf<CardCycleView?>(null) }
    val dm = DateTimeFormatter.ofPattern("dd MMM")
    val actualConfirmedTotal = if (confirmedTotal > BigDecimal.ZERO) confirmedTotal else {
        confirmedCards.fold(BigDecimal.ZERO) { total, card ->
            total + (card.totalDue ?: BigDecimal.ZERO)
        }
    }
    val currentSpendTotal = currentCycles.fold(BigDecimal.ZERO) { total, cycle ->
        total + cycle.currentSpend
    }
    val refundTotal = currentCycles.fold(BigDecimal.ZERO) { total, cycle -> total + cycle.refunds }
    val nextConfirmedDue = confirmedCards.mapNotNull { it.dueDate }.minOrNull()

    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Primary) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "CREDIT CARD CYCLES",
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CycleSummaryTile(
                    modifier = Modifier.weight(1f),
                    label = "Already billed",
                    amount = actualConfirmedTotal.rupees(),
                    supporting = nextConfirmedDue?.let { "Next due ${it.format(dm)}" } ?: "No bill due",
                    selected = selectedView == CardCycleView.BILLED,
                    onClick = {
                        selectedView = if (selectedView == CardCycleView.BILLED) {
                            null
                        } else {
                            CardCycleView.BILLED
                        }
                    },
                )
                CycleSummaryTile(
                    modifier = Modifier.weight(1f),
                    label = "Current cycle spend",
                    amount = currentSpendTotal.rupees(),
                    supporting = "Recorded through ${LocalDate.now().format(dm)}",
                    selected = selectedView == CardCycleView.CURRENT,
                    onClick = {
                        selectedView = if (selectedView == CardCycleView.CURRENT) {
                            null
                        } else {
                            CardCycleView.CURRENT
                        }
                    },
                )
            }

            when (selectedView) {
                CardCycleView.BILLED -> {
                    Text(
                        "BILLS ALREADY GENERATED",
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.OnSurfaceMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        confirmedCards.forEach { card ->
                            ConfirmedCardDueRow(card, dm, onOpenDetails)
                        }
                    }
                }
                CardCycleView.CURRENT -> {
                    Text(
                        "CURRENT BILLING CYCLES",
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.OnSurfaceMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        currentCycles.forEach { cycle ->
                            CurrentCardCycleRow(cycle, dm, onOpenCurrentCycle)
                        }
                    }
                    Text(
                        if (refundTotal > BigDecimal.ZERO) {
                            "${currentSpendTotal.rupees()} recorded spend · ${refundTotal.rupees()} refunds"
                        } else {
                            "${currentSpendTotal.rupees()} recorded across ${currentCycles.size} active card cycles"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
                null -> Unit
            }
        }
    }
}

@Composable
private fun CycleSummaryTile(
    modifier: Modifier,
    label: String,
    amount: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) LifeOsColors.Primary.copy(alpha = 0.12f) else LifeOsColors.SurfaceMuted,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
            Text(
                amount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) LifeOsColors.Primary else LifeOsColors.OnBackground,
            )
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun ConfirmedCardDueRow(
    card: CardStatement,
    dm: DateTimeFormatter,
    onOpenDetails: (Int) -> Unit,
) {
    val dueSoon = card.dueDate?.let {
        !it.isBefore(LocalDate.now()) && !it.isAfter(LocalDate.now().plusDays(7))
    } ?: false
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpenDetails(card.id) },
        shape = RoundedCornerShape(14.dp),
        color = LifeOsColors.SurfaceMuted,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.cardLabel + (card.cardLast4?.let { " ····$it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LifeOsColors.OnBackground,
                    maxLines = 1,
                )
                Text(
                    card.dueDate?.let { "Due ${it.format(dm)}" } ?: "Due date not listed",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dueSoon) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
                    fontWeight = if (dueSoon) FontWeight.SemiBold else null,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                (card.totalDue ?: BigDecimal.ZERO).rupees(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = LifeOsColors.OnBackground,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "Open ${card.cardLabel} statement",
                tint = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CurrentCardCycleRow(
    cycle: CurrentCardCycle,
    dm: DateTimeFormatter,
    onOpenDetails: (String, String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            onOpenDetails(cycle.bank, cycle.cardLast4)
        },
        shape = RoundedCornerShape(14.dp),
        color = LifeOsColors.SurfaceMuted,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    compactCardLabel(cycle.cardLabel, cycle.cardLast4),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LifeOsColors.OnBackground,
                    maxLines = 1,
                )
                Text(
                    "Cycle ${cycle.cycleStart.format(dm)} to ${cycle.cycleEnd.format(dm)} · Bills ${cycle.cycleEnd.format(dm)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                cycle.currentSpend.rupees(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = LifeOsColors.OnBackground,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "Open ${cycle.cardLabel} current cycle",
                tint = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
