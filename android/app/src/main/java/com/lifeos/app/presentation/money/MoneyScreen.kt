package com.lifeos.app.presentation.money

import androidx.compose.runtime.produceState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.RecurringCharge
import com.lifeos.app.domain.model.RepaymentCandidate
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.components.AreaHeader
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.theme.LifeOsColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Duration
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

internal fun formatSpendAmount(amount: BigDecimal): String = amount.rupees()

private val CURRENCY_SYMBOLS = mapOf("INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£")

/** Amount rendered in its own currency. INR keeps the Indian lakh/crore
 * grouping (₹12,34,567); every other currency uses plain thousands grouping
 * with its symbol where we know one, else the ISO code as a suffix ("23 AED")
 * so a foreign-currency alert never gets mislabeled as rupees. */
internal fun BigDecimal.money(currency: String): String {
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
    "vehicle" -> Color(0xFF557A95)       // steel blue
    "insurance" -> Color(0xFF6C789D)     // slate
    "taxes" -> Color(0xFF8B6F62)         // warm taupe
    "legal_fees" -> Color(0xFF8A8578)    // professional services / warm stone
    "emi" -> Color(0xFFD07B43)           // warm copper
    "emi_fees" -> Color(0xFFC99A3C)      // ochre
    "wallet_load" -> Color(0xFF5FA396)   // sea teal
    "travel" -> Color(0xFF58A3B4)        // lagoon
    else -> LifeOsColors.OnSurfaceMuted
}

internal fun prettyCategory(category: String): String = when (category.lowercase()) {
    "emi" -> "EMI"
    "emi_fees" -> "EMI fees"
    "vehicle" -> "Vehicle maintenance"
    "insurance" -> "Insurance"
    "taxes" -> "Taxes & government fees"
    // Keep the legacy key so old user labels and overrides continue to group
    // together, but give the category a useful life beyond one legal expense.
    "legal_fees" -> "Professional services"
    else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Household spend categories a budget makes sense for — excludes fixed EMI
 * commitments, which are tracked separately, not budgeted against. */
internal val BUDGETABLE_CATEGORIES = listOf(
    "food", "groceries", "shopping", "fuel", "subscriptions", "entertainment",
    "vehicle", "insurance", "taxes", "legal_fees", "travel", "bills", "utilities",
    "health",
)

private fun compactCardLabel(label: String, last4: String): String {
    val compact = label.replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+credit card$", RegexOption.IGNORE_CASE), "")
        .trim()
    return "$compact ····$last4"
}

private val EDITABLE_CATEGORIES = listOf(
    "internet", "utilities", "food", "groceries", "fuel", "travel", "shopping",
    "subscriptions", "entertainment", "medical", "education", "rent", "emi",
    "investments", "vehicle", "insurance", "taxes", "legal_fees", "other",
)

// --- period math ---------------------------------------------------------------
// PeriodSlice, sliceFor, previousPeriodSlice, CategoryTotal, and the category/
// total rollups live in MoneyAggregation.kt as plain, unit-testable functions.

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

private enum class CardCycleView { BILLED, CURRENT }

internal fun filterEmiTransactions(
    transactions: List<Transaction>,
    includeEmi: Boolean,
): List<Transaction> = if (includeEmi) {
    transactions
} else {
    transactions.filterNot { it.spendCategory in EMI_SPEND_CATEGORIES }
}

internal fun sortTransactions(
    transactions: List<Transaction>,
    sort: TransactionSort,
): List<Transaction> = when (sort) {
    TransactionSort.RECENT -> transactions.sortedWith(
        compareByDescending<Transaction> { it.txnDate }.thenByDescending { it.txnAt },
    )
    TransactionSort.OLDEST -> transactions.sortedWith(
        compareBy<Transaction> { it.txnDate }.thenBy { it.txnAt },
    )
    TransactionSort.HIGHEST -> transactions.sortedWith(
        compareByDescending<Transaction> { it.amount }
            .thenByDescending { it.txnDate }
            .thenByDescending { it.txnAt },
    )
    TransactionSort.LOWEST -> transactions.sortedWith(
        compareBy<Transaction> { it.amount }
            .thenByDescending { it.txnDate }
            .thenByDescending { it.txnAt },
    )
}

@Composable
fun MoneyScreen(
    overviewRequestId: Long = 0L,
    navigationDrawerOpen: Boolean = false,
    onOpenStatement: (Int) -> Unit = {},
    onOpenCurrentCycle: (String, String) -> Unit = { _, _ -> },
    viewModel: MoneyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRangePicker by remember { mutableStateOf(false) }
    var showPeriodMenu by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showAllCategories by remember { mutableStateOf(false) }
    var showRecurringSheet by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sectionScrollStates = MoneySection.entries.map { rememberLazyListState() }

    // A root Money section has an in-app way home as well as Android Back.
    // Modal sheets/dialogs declared below retain first refusal on Back.
    BackHandler(enabled = !navigationDrawerOpen && state.section != MoneySection.OVERVIEW) {
        viewModel.setSection(MoneySection.OVERVIEW)
    }

    LaunchedEffect(overviewRequestId) {
        if (overviewRequestId != 0L) viewModel.setSection(MoneySection.OVERVIEW)
    }

    val slice = remember(state.period, state.anchor, state.customStart, state.customEnd) {
        sliceFor(state.period, state.anchor, state.customStart, state.customEnd)
    }
    val spendableTransactions = remember(state.transactions) {
        state.transactions.filter {
            it.direction == "debit" && it.spendCategory != null
        }
    }
    val bankUpiAccounts = remember(state.transactions) {
        bankUpiAccountOptions(state.transactions)
    }
    val bankUpiKeys = remember(bankUpiAccounts) { bankUpiAccounts.map { it.key }.toSet() }
    val sourceFilteredTransactions = remember(spendableTransactions, state.bankUpiFilter, bankUpiKeys) {
        when (val sourceFilter = state.bankUpiFilter) {
            null -> spendableTransactions
            ALL_BANK_UPI_FILTER -> spendableTransactions.filter {
                it.bankUpiAccountKey(bankUpiKeys) != null
            }
            else -> spendableTransactions.filter {
                it.bankUpiAccountKey(bankUpiKeys) == sourceFilter
            }
        }
    }
    val hasEmi = remember(sourceFilteredTransactions) {
        sourceFilteredTransactions.any { it.spendCategory in EMI_SPEND_CATEGORIES }
    }
    val emiFilteredTransactions = remember(sourceFilteredTransactions, state.includeEmi) {
        filterEmiTransactions(sourceFilteredTransactions, state.includeEmi)
    }
    val sliceBeforeCategory = remember(emiFilteredTransactions, slice) {
        emiFilteredTransactions.inSlice(slice)
    }
    val availableCategories = remember(sliceBeforeCategory) {
        categoryTotals(sliceBeforeCategory).map { it.category }
    }
    val filter = state.categoryFilter
    val categoryFilteredTransactions = remember(emiFilteredTransactions, filter) {
        if (filter.isEmpty()) {
            emiFilteredTransactions
        } else {
            emiFilteredTransactions.filter { it.spendCategory in filter }
        }
    }
    val spendTxns = remember(categoryFilteredTransactions, slice) {
        categoryFilteredTransactions.inSlice(slice)
    }
    val total = remember(spendTxns) { spendTxns.totalAmount() }
    val overviewTotal = remember(sliceBeforeCategory) { sliceBeforeCategory.totalAmount() }
    val byCategory = remember(sliceBeforeCategory) { categoryTotals(sliceBeforeCategory) }
    val canGoNext = state.period != SpendPeriod.CUSTOM && slice.end.isBefore(LocalDate.now())
    val canGoPrev = state.period != SpendPeriod.CUSTOM
    val recentTransactions = remember(sliceBeforeCategory) {
        sortTransactions(sliceBeforeCategory, TransactionSort.RECENT).take(5)
    }
    val orderedTransactions = remember(spendTxns, state.transactionSort) {
        sortTransactions(spendTxns, state.transactionSort)
    }
    val transactionGroups = remember(orderedTransactions, state.transactionSort) {
        if (state.transactionSort == TransactionSort.RECENT || state.transactionSort == TransactionSort.OLDEST) {
            orderedTransactions.groupBy { it.txnDate }
        } else {
            emptyMap()
        }
    }
    val todayTotal = remember(emiFilteredTransactions) {
        val today = LocalDate.now()
        emiFilteredTransactions.filter { it.txnDate == today }.totalAmount()
    }
    val previousSlice = remember(state.period, slice) { previousPeriodSlice(state.period, slice) }
    val previousTotal = remember(emiFilteredTransactions, previousSlice) {
        previousSlice?.let { previous -> emiFilteredTransactions.inSlice(previous).totalAmount() }
    }
    val activeFilterCount = filter.size +
        (if (state.bankUpiFilter != null) 1 else 0) +
        (if (state.includeEmi) 1 else 0)

    if (showFilters) {
        MoneyFiltersSheet(
            period = state.period,
            availableCategories = availableCategories,
            selectedCategories = filter,
            bankUpiFilter = state.bankUpiFilter,
            bankUpiAccounts = bankUpiAccounts,
            hasEmi = hasEmi,
            includeEmi = state.includeEmi,
            onSelectPeriod = { period ->
                if (period == SpendPeriod.CUSTOM) {
                    showFilters = false
                    showRangePicker = true
                } else {
                    viewModel.setPeriod(period)
                }
            },
            onToggleCategory = viewModel::toggleCategoryFilter,
            onClearCategories = viewModel::clearCategoryFilter,
            onBankUpiFilterChange = viewModel::setBankUpiFilter,
            onToggleEmi = viewModel::toggleIncludeEmi,
            onDismiss = { showFilters = false },
        )
    }

    if (showSort) {
        TransactionSortSheet(
            selected = state.transactionSort,
            onSelect = { sort ->
                viewModel.setTransactionSort(sort)
                showSort = false
            },
            onDismiss = { showSort = false },
        )
    }

    if (showAllCategories) {
        AllCategoriesSheet(
            categories = byCategory,
            total = overviewTotal,
            onSelect = { category ->
                showAllCategories = false
                viewModel.clearCategoryFilter()
                viewModel.toggleCategoryFilter(category)
                viewModel.setSection(MoneySection.TRANSACTIONS)
            },
            onSetBudget = { category -> viewModel.beginBudgetEdit(category) },
            onDismiss = { showAllCategories = false },
        )
    }

    if (showRecurringSheet) {
        RecurringChargesSheet(
            charges = state.recurringCharges,
            onDismiss = { showRecurringSheet = false },
        )
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

    selectedTransaction?.let { transaction ->
        TransactionDetailDialog(
            transaction = transaction,
            placeFor = viewModel::placeFor,
            onDismiss = { selectedTransaction = null },
            onEditCategory = {
                selectedTransaction = null
                viewModel.beginCategoryEdit(transaction)
            },
            onMarkAsRepaid = {
                selectedTransaction = null
                viewModel.beginRepayment(transaction)
            },
            onMarkAsRefunded = {
                selectedTransaction = null
                viewModel.beginRefund(transaction)
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

    state.budgetEditCategory?.let { category ->
        BudgetEditDialog(
            category = category,
            existingLimit = state.budgets.firstOrNull { it.category == category }?.monthlyLimit,
            saving = state.budgetSaving,
            error = state.budgetError,
            onSave = viewModel::saveBudget,
            onRemove = viewModel::removeBudget,
            onDismiss = viewModel::dismissBudgetEdit,
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

    state.refundDebit?.let { debit ->
        RefundDialog(
            debit = debit,
            saving = state.refundSaving,
            error = state.refundError,
            onConfirm = viewModel::confirmRefund,
            onDismiss = viewModel::dismissRefund,
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

    LaunchedEffect(state.completedRefund?.id) {
        val refund = state.completedRefund ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Refund saved. The purchase is excluded from spend.",
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoRefund(refund.id)
        }
        viewModel.consumeCompletedRefund()
    }

    Box(modifier = Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            MoneyTopBar(lastSyncAt = state.lastSyncAt)
            Column(Modifier.padding(horizontal = 20.dp)) {
                MoneySectionTabs(selected = state.section, onSelect = viewModel::setSection)
                Spacer(Modifier.height(16.dp))
            }
            LazyColumn(
                state = sectionScrollStates[state.section.ordinal],
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                state.error?.let { error ->
                    item {
                        CachedDataBanner(error, state.lastSyncAt)
                        Spacer(Modifier.height(14.dp))
                    }
                }

                if (state.loading && state.transactions.isEmpty()) {
                    item { MoneyLoadingState() }
                } else {
                    when (state.section) {
                        MoneySection.OVERVIEW -> {
                            item {
                                PeriodSelector(
                                    label = periodLabel(state.period, state.anchor, slice),
                                    expanded = showPeriodMenu,
                                    canGoPrev = canGoPrev,
                                    canGoNext = canGoNext,
                                    onToggleMenu = { showPeriodMenu = !showPeriodMenu },
                                    onDismissMenu = { showPeriodMenu = false },
                                    onSelectPeriod = { period ->
                                        showPeriodMenu = false
                                        if (period == SpendPeriod.CUSTOM) showRangePicker = true
                                        else viewModel.setPeriod(period)
                                    },
                                    onPrev = viewModel::prevPeriod,
                                    onNext = viewModel::nextPeriod,
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            item {
                                OverviewSpendCard(
                                    total = overviewTotal,
                                    transactionCount = sliceBeforeCategory.size,
                                    scopeLabel = listOfNotNull(
                                        if (state.includeEmi) "EMI included" else "EMI excluded",
                                        state.bankUpiFilter?.let { key ->
                                            bankUpiAccounts.firstOrNull { it.key == key }?.label ?: "Bank UPI"
                                        },
                                    ).joinToString(" · "),
                                    todayTotal = todayTotal,
                                    previousTotal = previousTotal,
                                    period = state.period,
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            if (state.cardDues.isNotEmpty()) {
                                item {
                                    UpcomingBillsRow(
                                        totalDue = state.totalDue ?: BigDecimal.ZERO,
                                        cards = state.cardDues,
                                        onClick = { viewModel.setSection(MoneySection.CARDS) },
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                            } else {
                                item { Spacer(Modifier.height(12.dp)) }
                            }
                            if (state.period in setOf(
                                    SpendPeriod.WEEK,
                                    SpendPeriod.MONTH,
                                    SpendPeriod.SIX_MONTHS,
                                    SpendPeriod.YEAR,
                                )
                            ) {
                                item {
                                    SpendTrendChart(
                                        transactions = sliceBeforeCategory,
                                        period = state.period,
                                        start = slice.start,
                                        end = slice.end,
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                            item {
                                MoneySectionHeading(
                                    title = "Recent activity",
                                    subtitle = "Latest recorded spending",
                                    action = "View all",
                                    onAction = {
                                        viewModel.clearCategoryFilter()
                                        viewModel.setSection(MoneySection.TRANSACTIONS)
                                    },
                                )
                                if (recentTransactions.isEmpty()) {
                                    EmptyInlineMessage("No spend recorded for this period.")
                                } else {
                                    Column {
                                        recentTransactions.forEach { transaction ->
                                            TransactionRow(
                                                txn = transaction,
                                                onClick = { selectedTransaction = transaction },
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                            if (state.budgets.isNotEmpty()) {
                                item {
                                    BudgetsSection(
                                        budgets = state.budgets,
                                        transactions = spendableTransactions,
                                        onSelect = { category ->
                                            viewModel.clearCategoryFilter()
                                            viewModel.toggleCategoryFilter(category)
                                            viewModel.setSection(MoneySection.TRANSACTIONS)
                                        },
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                            if (byCategory.isNotEmpty()) {
                                item {
                                    TopCategoriesSection(
                                        categories = byCategory.take(5),
                                        total = overviewTotal,
                                        categoryCount = byCategory.size,
                                        onViewAll = { showAllCategories = true },
                                        onSelect = { category ->
                                            viewModel.clearCategoryFilter()
                                            viewModel.toggleCategoryFilter(category)
                                            viewModel.setSection(MoneySection.TRANSACTIONS)
                                        },
                                    )
                                }
                            }
                            if (state.recurringCharges.isNotEmpty()) {
                                item {
                                    Spacer(Modifier.height(24.dp))
                                    RecurringSummaryRow(
                                        charges = state.recurringCharges,
                                        onOpen = { showRecurringSheet = true },
                                    )
                                }
                            }
                        }

                        MoneySection.TRANSACTIONS -> {
                            item {
                                TransactionListHeader(
                                    label = periodLabel(state.period, state.anchor, slice),
                                    transactionCount = spendTxns.size,
                                    total = total,
                                    filterCount = activeFilterCount,
                                    sort = state.transactionSort,
                                    onOpenSort = { showSort = true },
                                    onOpenFilters = { showFilters = true },
                                )
                                if (activeFilterCount > 0) {
                                    ActiveFiltersRow(
                                        selectedCategories = filter,
                                        bankUpiFilter = state.bankUpiFilter,
                                        bankUpiAccounts = bankUpiAccounts,
                                        includeEmi = state.includeEmi,
                                        onClearCategories = viewModel::clearCategoryFilter,
                                        onClearBank = { viewModel.setBankUpiFilter(null) },
                                        onToggleEmi = viewModel::toggleIncludeEmi,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            if (orderedTransactions.isEmpty()) {
                                item { EmptyInlineMessage("No spend matches these filters.") }
                            } else if (
                                state.transactionSort == TransactionSort.HIGHEST ||
                                state.transactionSort == TransactionSort.LOWEST
                            ) {
                                orderedTransactions.forEach { transaction ->
                                    item(key = "transaction-${transaction.id}") {
                                        TransactionRow(
                                            txn = transaction,
                                            onClick = { selectedTransaction = transaction },
                                        )
                                    }
                                }
                            } else {
                                transactionGroups.forEach { (date, transactions) ->
                                    item {
                                        TransactionDayGroup(
                                            date = date,
                                            transactions = transactions,
                                            onSelectTransaction = { selectedTransaction = it },
                                        )
                                    }
                                }
                            }
                        }

                        MoneySection.CARDS -> {
                            item {
                                CardsSummary(
                                    billedTotal = state.totalDue ?: BigDecimal.ZERO,
                                    billedCount = state.cardDues.size,
                                    currentTotal = state.currentCardCycles.fold(BigDecimal.ZERO) { sum, cycle ->
                                        sum + cycle.currentSpend
                                    },
                                )
                                Spacer(Modifier.height(24.dp))
                            }
                            item {
                                MoneySectionHeading(
                                    title = "Already billed",
                                    subtitle = "Statements awaiting payment",
                                )
                                if (state.cardDues.isEmpty()) {
                                    EmptyInlineMessage("No card bills are currently due.")
                                } else {
                                    Column {
                                        state.cardDues.forEach { card ->
                                            BilledCardRow(card, onOpenStatement)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                            item {
                                MoneySectionHeading(
                                    title = "Current cycles",
                                    subtitle = "Spend since the latest statement",
                                )
                                if (state.currentCardCycles.isEmpty()) {
                                    EmptyInlineMessage("No current card-cycle data is available.")
                                } else {
                                    Column {
                                        state.currentCardCycles.forEach { cycle ->
                                            CurrentCycleRow(cycle, onOpenCurrentCycle)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(36.dp)) }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun MoneyTopBar(lastSyncAt: Long?) {
    AreaHeader(
        title = "Money",
        subtitleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(
                    when {
                        lastSyncAt == null -> LifeOsColors.OnSurfaceMuted
                        System.currentTimeMillis() - lastSyncAt > Duration.ofDays(1).toMillis() -> LifeOsColors.Warning
                        else -> LifeOsColors.Positive
                    },
                ))
                Spacer(Modifier.width(7.dp))
                Text(
                    syncFreshness(lastSyncAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        },
    )
}

private fun syncFreshness(lastSyncAt: Long?): String {
    if (lastSyncAt == null) return "Waiting for first sync"
    val elapsed = Duration.ofMillis((System.currentTimeMillis() - lastSyncAt).coerceAtLeast(0L))
    return when {
        elapsed.toMinutes() < 1 -> "Synced just now"
        elapsed.toMinutes() < 60 -> "Synced ${elapsed.toMinutes()} min ago"
        elapsed.toHours() < 24 -> "Synced ${elapsed.toHours()} h ago"
        elapsed.toDays() == 1L -> "Synced yesterday"
        else -> "Synced ${elapsed.toDays()} days ago"
    }
}

@Composable
internal fun MoneySectionTabs(selected: MoneySection, onSelect: (MoneySection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(LifeOsColors.SurfaceMuted).padding(4.dp),
    ) {
        listOf(
            MoneySection.OVERVIEW to "Overview",
            MoneySection.TRANSACTIONS to "Transactions",
            MoneySection.CARDS to "Cards",
        ).forEach { (section, label) ->
            val active = selected == section
            Surface(
                modifier = Modifier.weight(1f).selectable(
                    selected = active,
                    onClick = { onSelect(section) },
                    role = Role.Tab,
                ),
                shape = RoundedCornerShape(10.dp),
                color = if (active) LifeOsColors.Surface else Color.Transparent,
                shadowElevation = if (active) 1.dp else 0.dp,
            ) {
                Text(
                    label,
                    modifier = Modifier.heightIn(min = 44.dp).padding(vertical = 13.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) LifeOsColors.Primary else LifeOsColors.OnSurfaceMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun PeriodSelector(
    label: String,
    expanded: Boolean,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onSelectPeriod: (SpendPeriod) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, enabled = canGoPrev, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous period")
        }
        Box(modifier = Modifier.weight(1f)) {
            TextButton(onClick = onToggleMenu, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismissMenu) {
                listOf(
                    SpendPeriod.DAY to "Day",
                    SpendPeriod.WEEK to "Week",
                    SpendPeriod.MONTH to "Month",
                    SpendPeriod.SIX_MONTHS to "6 months",
                    SpendPeriod.YEAR to "Year",
                    SpendPeriod.CUSTOM to "Custom range",
                ).forEach { (period, periodLabel) ->
                    DropdownMenuItem(
                        text = { Text(periodLabel) },
                        onClick = { onSelectPeriod(period) },
                    )
                }
            }
        }
        IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Next period")
        }
    }
}

@Composable
private fun OverviewSpendCard(
    total: BigDecimal,
    transactionCount: Int,
    scopeLabel: String,
    todayTotal: BigDecimal,
    previousTotal: BigDecimal?,
    period: SpendPeriod,
) {
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Primary) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Spent", style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnSurfaceMuted)
                Text(
                    "$transactionCount transaction${if (transactionCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            Text(
                total.rupees(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = LifeOsColors.OnBackground,
                modifier = Modifier.padding(top = 5.dp, bottom = 15.dp),
            )
            Text(
                scopeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(LifeOsColors.SurfaceBorder))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                SummarySupportValue("Today", todayTotal.rupees(), Modifier.weight(1f))
                SummarySupportValue(
                    previousPeriodLabel(period),
                    comparisonText(total, previousTotal),
                    Modifier.weight(1f),
                    positive = previousTotal != null && total <= previousTotal,
                )
            }
        }
    }
}

private fun previousPeriodLabel(period: SpendPeriod): String = when (period) {
    SpendPeriod.DAY -> "vs yesterday"
    SpendPeriod.WEEK -> "vs previous week"
    SpendPeriod.MONTH -> "vs previous month"
    SpendPeriod.SIX_MONTHS -> "vs previous 6 months"
    SpendPeriod.YEAR -> "vs previous year"
    SpendPeriod.CUSTOM -> "vs previous range"
}

private fun comparisonText(total: BigDecimal, previous: BigDecimal?): String {
    if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) return "No prior spend"
    val difference = total.subtract(previous).abs().rupees()
    return when {
        total < previous -> "$difference less"
        total > previous -> "$difference more"
        else -> "No change"
    }
}

@Composable
private fun SummarySupportValue(label: String, value: String, modifier: Modifier, positive: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (positive) LifeOsColors.Positive else LifeOsColors.OnBackground,
        )
    }
}

@Composable
private fun UpcomingBillsRow(totalDue: BigDecimal, cards: List<CardStatement>, onClick: () -> Unit) {
    val today = LocalDate.now()
    val nextDue = cards.mapNotNull { it.dueDate }.minOrNull()
    val days = nextDue?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
    val urgent = days != null && days in 0..7
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (urgent) LifeOsColors.Warning.copy(alpha = 0.10f) else LifeOsColors.SurfaceMuted,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (urgent) LifeOsColors.Warning.copy(alpha = 0.45f) else LifeOsColors.SurfaceBorder,
        ),
    ) {
        Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(11.dp), color = LifeOsColors.PrimarySoft) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CreditCard, contentDescription = null, tint = LifeOsColors.Primary)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${totalDue.rupees()} due across ${cards.size} card${if (cards.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LifeOsColors.OnBackground,
                )
                Text(
                    nextDue?.format(DateTimeFormatter.ofPattern("'Next due' dd MMM")) ?: "Due date unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            if (urgent) {
                Text(
                    if (days == 0L) "Today" else "$days days",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = LifeOsColors.Warning,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Open cards", tint = LifeOsColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun MoneySectionHeading(
    title: String,
    subtitle: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = LifeOsColors.OnBackground)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun BudgetsSection(
    budgets: List<com.lifeos.app.domain.model.Budget>,
    transactions: List<Transaction>,
    onSelect: (String) -> Unit,
) {
    val paces = remember(budgets, transactions) {
        budgets.map { budget -> budgetPace(budget.category, budget.monthlyLimit, transactions) }
            .sortedByDescending { it.spentSoFar }
    }
    MoneySectionHeading(title = "Budgets", subtitle = "Pace this month, computed offline")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        paces.forEach { pace -> BudgetPaceRow(pace, onSelect) }
    }
}

@Composable
private fun BudgetPaceRow(pace: BudgetPace, onSelect: (String) -> Unit) {
    val fraction = if (pace.monthlyLimit > BigDecimal.ZERO) {
        (pace.spentSoFar.toDouble() / pace.monthlyLimit.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val barColor = if (pace.onPaceToExceed) LifeOsColors.Warning else categoryColor(pace.category)
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(pace.category) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                prettyCategory(pace.category),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                "${pace.spentSoFar.rupees()} / ${pace.monthlyLimit.rupees()}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(LifeOsColors.SurfaceMuted)) {
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.03f)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(barColor))
        }
        Text(
            if (pace.onPaceToExceed) {
                val day = pace.projectedExceedDay
                if (day != null) "On pace to exceed by day $day" else "On pace to exceed this month"
            } else {
                "On track · projected ${pace.projectedMonthTotal.rupees()}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (pace.onPaceToExceed) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
        )
    }
}

/** Compact Overview row: count + soonest upcoming charge, tap to open the full list. */
@Composable
private fun RecurringSummaryRow(charges: List<RecurringCharge>, onOpen: () -> Unit) {
    val next = charges.minByOrNull { it.nextExpectedDate }
    val priceIncreases = charges.count { (it.priceChangePct ?: 0.0) > 0.0 }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = LifeOsColors.Surface,
        border = BorderStroke(1.dp, LifeOsColors.SurfaceBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Recurring", style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("${charges.size} detected")
                        next?.let { append(" · next ${prettyCategory(it.counterparty)} ${it.nextExpectedDate}") }
                        if (priceIncreases > 0) append(" · $priceIncreases price ${if (priceIncreases == 1) "increase" else "increases"}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Open recurring charges", tint = LifeOsColors.OnSurfaceMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringChargesSheet(charges: List<RecurringCharge>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LifeOsColors.SurfaceElevated) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Recurring charges", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Detected from repeating debits — never used to forecast totals.",
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(charges, key = { it.counterparty + it.lastDate }) { charge -> RecurringChargeRow(charge) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun RecurringChargeRow(charge: RecurringCharge) {
    val increased = (charge.priceChangePct ?: 0.0) > 0.0
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                prettyCategory(charge.counterparty),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                charge.lastAmount.rupees(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${charge.cadence.replaceFirstChar { it.uppercase() }} · next ~${charge.nextExpectedDate}",
                style = MaterialTheme.typography.labelSmall,
                color = LifeOsColors.OnSurfaceMuted,
            )
            if (increased) {
                Surface(shape = RoundedCornerShape(6.dp), color = LifeOsColors.Warning.copy(alpha = 0.12f)) {
                    Text(
                        "+${"%.0f".format(charge.priceChangePct)}%",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.Warning,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopCategoriesSection(
    categories: List<CategoryTotal>,
    total: BigDecimal,
    categoryCount: Int,
    onViewAll: () -> Unit,
    onSelect: (String) -> Unit,
) {
    MoneySectionHeading(
        title = "Top categories",
        subtitle = "$categoryCount categories · Tap to see transactions",
        action = if (categoryCount > categories.size) "View all" else null,
        onAction = if (categoryCount > categories.size) onViewAll else null,
    )
    val max = categories.maxOfOrNull { it.total.toDouble() }?.coerceAtLeast(1.0) ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        categories.forEach { category ->
            CategorySpendRow(category, total, max, onSelect)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllCategoriesSheet(
    categories: List<CategoryTotal>,
    total: BigDecimal,
    onSelect: (String) -> Unit,
    onSetBudget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val max = categories.maxOfOrNull { it.total.toDouble() }?.coerceAtLeast(1.0) ?: 1.0
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LifeOsColors.SurfaceElevated) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("All categories", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${categories.size} categories in this period · tap ${'◎'} to set a budget",
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(categories, key = { it.category }) { category ->
                    CategorySpendRow(category, total, max, onSelect, onSetBudget)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun CategorySpendRow(
    category: CategoryTotal,
    total: BigDecimal,
    max: Double,
    onSelect: (String) -> Unit,
    onSetBudget: ((String) -> Unit)? = null,
) {
    val share = if (total > BigDecimal.ZERO) category.total.toDouble() / total.toDouble() * 100 else 0.0
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(category.category) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                prettyCategory(category.category),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                "${category.total.rupees()} · ${share.toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (onSetBudget != null) {
                IconButton(
                    onClick = { onSetBudget(category.category) },
                    modifier = Modifier.size(32.dp).padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.Rounded.TrackChanges,
                        contentDescription = "Set budget for ${prettyCategory(category.category)}",
                        tint = LifeOsColors.OnSurfaceMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(LifeOsColors.SurfaceMuted)) {
            Box(
                Modifier.fillMaxWidth((category.total.toDouble() / max).toFloat().coerceIn(0.03f, 1f))
                    .height(5.dp).clip(RoundedCornerShape(3.dp)).background(categoryColor(category.category)),
            )
        }
    }
}

@Composable
private fun TransactionListHeader(
    label: String,
    transactionCount: Int,
    total: BigDecimal,
    filterCount: Int,
    sort: TransactionSort,
    onOpenSort: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = LifeOsColors.OnBackground)
            Text(
                "$transactionCount transaction${if (transactionCount == 1) "" else "s"} · ${total.rupees()}",
                style = MaterialTheme.typography.labelSmall,
                color = LifeOsColors.OnSurfaceMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TransactionHeaderControl(
                label = sort.shortLabel,
                icon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(17.dp)) },
                contentDescription = "Sort transactions: ${sort.optionLabel}",
                onClick = onOpenSort,
            )
            TransactionHeaderControl(
                label = if (filterCount == 0) "Filters" else "Filters · $filterCount",
                icon = { Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(17.dp)) },
                contentDescription = if (filterCount == 0) "Filter transactions" else "Filter transactions, $filterCount active",
                onClick = onOpenFilters,
            )
        }
    }
}

private val TransactionSort.shortLabel: String
    get() = when (this) {
        TransactionSort.RECENT -> "Recent"
        TransactionSort.OLDEST -> "Oldest"
        TransactionSort.HIGHEST -> "Highest"
        TransactionSort.LOWEST -> "Lowest"
    }

private val TransactionSort.optionLabel: String
    get() = when (this) {
        TransactionSort.RECENT -> "Recent first"
        TransactionSort.OLDEST -> "Oldest first"
        TransactionSort.HIGHEST -> "Highest amount"
        TransactionSort.LOWEST -> "Lowest amount"
    }

@Composable
private fun TransactionHeaderControl(
    label: String,
    icon: @Composable () -> Unit,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.semantics { this.contentDescription = contentDescription }.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = LifeOsColors.Surface,
        contentColor = LifeOsColors.Primary,
        border = androidx.compose.foundation.BorderStroke(1.dp, LifeOsColors.SurfaceBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionSortSheet(
    selected: TransactionSort,
    onSelect: (TransactionSort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LifeOsColors.SurfaceElevated) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .navigationBarsPadding(),
        ) {
            Text("Sort transactions", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Choose how this list is ordered",
                style = MaterialTheme.typography.bodyMedium,
                color = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
            )
            TransactionSort.entries.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = option == selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(option.optionLabel, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (option) {
                                TransactionSort.RECENT -> "Newest dates at the top"
                                TransactionSort.OLDEST -> "Earliest dates at the top"
                                TransactionSort.HIGHEST -> "Largest payments at the top"
                                TransactionSort.LOWEST -> "Smallest payments at the top"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveFiltersRow(
    selectedCategories: Set<String>,
    bankUpiFilter: String?,
    bankUpiAccounts: List<BankUpiAccountOption>,
    includeEmi: Boolean,
    onClearCategories: () -> Unit,
    onClearBank: () -> Unit,
    onToggleEmi: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (selectedCategories.isNotEmpty()) {
            FilterChip(
                label = selectedCategories.joinToString { prettyCategory(it) },
                active = true,
                color = LifeOsColors.Primary,
                showDot = false,
                onClick = onClearCategories,
            )
        }
        if (bankUpiFilter != null) {
            val label = if (bankUpiFilter == ALL_BANK_UPI_FILTER) "Bank UPI · All" else {
                bankUpiAccounts.firstOrNull { it.key == bankUpiFilter }?.label ?: "Bank UPI"
            }
            FilterChip(label, true, LifeOsColors.Secondary, false, onClearBank)
        }
        if (includeEmi) {
            FilterChip("EMI included", true, categoryColor("emi"), false, onToggleEmi)
        }
    }
}

@Composable
private fun TransactionDayGroup(
    date: LocalDate,
    transactions: List<Transaction>,
    onSelectTransaction: (Transaction) -> Unit,
) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("dd MMMM"))
    }
    val total = transactions.fold(BigDecimal.ZERO) { sum, transaction -> sum + transaction.amount }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted)
        Text(total.rupees(), style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted)
    }
    Column {
        transactions.forEach { transaction ->
            TransactionRow(transaction, onClick = { onSelectTransaction(transaction) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoneyFiltersSheet(
    period: SpendPeriod,
    availableCategories: List<String>,
    selectedCategories: Set<String>,
    bankUpiFilter: String?,
    bankUpiAccounts: List<BankUpiAccountOption>,
    hasEmi: Boolean,
    includeEmi: Boolean,
    onSelectPeriod: (SpendPeriod) -> Unit,
    onToggleCategory: (String) -> Unit,
    onClearCategories: () -> Unit,
    onBankUpiFilterChange: (String?) -> Unit,
    onToggleEmi: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LifeOsColors.SurfaceElevated) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Filters", style = MaterialTheme.typography.headlineMedium)
                    Text("Refine the transaction list", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                }
                Button(onClick = onDismiss) { Text("Done") }
            }
            Text("Period", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    SpendPeriod.DAY to "Day",
                    SpendPeriod.WEEK to "Week",
                    SpendPeriod.MONTH to "Month",
                    SpendPeriod.SIX_MONTHS to "6 months",
                    SpendPeriod.YEAR to "Year",
                    SpendPeriod.CUSTOM to "Custom",
                ).forEach { (option, label) ->
                    FilterChip(label, period == option, LifeOsColors.Primary, false) { onSelectPeriod(option) }
                }
            }
            Text("Categories", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip("All", selectedCategories.isEmpty(), LifeOsColors.Primary, false, onClearCategories)
                availableCategories.forEach { category ->
                    FilterChip(
                        prettyCategory(category),
                        category in selectedCategories,
                        categoryColor(category),
                        true,
                    ) { onToggleCategory(category) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Account", style = MaterialTheme.typography.titleSmall)
                    Text("All spend or a proven bank UPI source", style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
                }
                BankUpiFilterMenu(bankUpiFilter, bankUpiAccounts, onBankUpiFilterChange)
            }
            if (hasEmi) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include EMI", style = MaterialTheme.typography.titleSmall)
                        Text("Hidden from everyday spend by default", style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
                    }
                    Switch(checked = includeEmi, onCheckedChange = { onToggleEmi() })
                }
            }
        }
    }
}

@Composable
private fun CardsSummary(billedTotal: BigDecimal, billedCount: Int, currentTotal: BigDecimal) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CardSummaryTile("Already billed", billedTotal.rupees(), "$billedCount card${if (billedCount == 1) "" else "s"}", Modifier.weight(1f))
        CardSummaryTile("Current cycles", currentTotal.rupees(), "Recorded so far", Modifier.weight(1f))
    }
}

@Composable
private fun CardSummaryTile(label: String, amount: String, supporting: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = LifeOsColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, LifeOsColors.SurfaceBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
            Text(amount, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun BilledCardRow(card: CardStatement, onOpenStatement: (Int) -> Unit) {
    val today = LocalDate.now()
    val days = card.dueDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
    val urgent = days != null && days in 0..7
    CreditCardRow(
        label = card.cardLabel + (card.cardLast4?.let { " ····$it" } ?: ""),
        supporting = card.dueDate?.let {
            val dayCopy = when (days) {
                0L -> "today"
                1L -> "tomorrow"
                else -> if (urgent) "$days days" else null
            }
            listOfNotNull("Due ${it.format(DateTimeFormatter.ofPattern("dd MMM"))}", dayCopy).joinToString(" · ")
        } ?: "Due date unavailable",
        amount = (card.totalDue ?: BigDecimal.ZERO).rupees(),
        urgent = urgent,
        onClick = { onOpenStatement(card.id) },
    )
}

@Composable
private fun CurrentCycleRow(cycle: CurrentCardCycle, onOpenCurrentCycle: (String, String) -> Unit) {
    CreditCardRow(
        label = compactCardLabel(cycle.cardLabel, cycle.cardLast4),
        supporting = "${cycle.cycleStart.format(DateTimeFormatter.ofPattern("dd MMM"))} to ${cycle.cycleEnd.format(DateTimeFormatter.ofPattern("dd MMM"))} · ${cycle.transactionCount} transactions",
        amount = cycle.currentSpend.rupees(),
        urgent = false,
        onClick = { onOpenCurrentCycle(cycle.bank, cycle.cardLast4) },
    )
}

@Composable
private fun CreditCardRow(label: String, supporting: String, amount: String, urgent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = LifeOsColors.PrimarySoft) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Text(label.firstOrNull()?.uppercase() ?: "C", fontWeight = FontWeight.Bold, color = LifeOsColors.Primary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = if (urgent) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
                fontWeight = if (urgent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = LifeOsColors.OnSurfaceMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CachedDataBanner(error: String, lastSyncAt: Long?) {
    val asOf = lastSyncAt?.let {
        val time = java.time.Instant.ofEpochMilli(it)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        " · as of $time"
    } ?: ""
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = LifeOsColors.Warning.copy(alpha = 0.10f),
    ) {
        Text(
            "$error$asOf",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = LifeOsColors.Warning,
        )
    }
}

@Composable
private fun MoneyLoadingState() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { index ->
            Surface(
                modifier = Modifier.fillMaxWidth().height(if (index == 0) 154.dp else 72.dp),
                shape = RoundedCornerShape(18.dp),
                color = LifeOsColors.SurfaceMuted,
            ) {}
        }
    }
}

@Composable
private fun EmptyInlineMessage(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = LifeOsColors.OnSurfaceMuted,
    )
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
    hasEmi: Boolean,
    includeEmi: Boolean,
    onToggleEmi: () -> Unit,
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
        if (hasEmi) {
            FilterChip(
                label = "Include EMI",
                active = includeEmi,
                color = categoryColor("emi"),
                showDot = true,
                onClick = onToggleEmi,
            )
        }
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
    emiExcluded: Boolean,
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
                        txnCount == 0 && emiExcluded -> "No everyday spend · EMI excluded"
                        txnCount == 0 -> "No spend recorded"
                        emiExcluded && filtered ->
                            "$txnCount transaction${if (txnCount == 1) "" else "s"} · EMI excluded · filtered"
                        emiExcluded ->
                            "$txnCount transaction${if (txnCount == 1) "" else "s"} · EMI excluded"
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
    onMarkAsRefunded: (Transaction) -> Unit,
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
            onMarkAsRefunded = {
                selectedTxn = null
                onMarkAsRefunded(txn)
            },
        )
    }
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = categoryColor(category)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to categories", tint = LifeOsColors.Primary)
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
    val merchant = txn.counterparty?.takeIf { it.isNotBlank() } ?: txn.source
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(11.dp), color = LifeOsColors.SurfaceMuted) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Text(
                    merchant.firstOrNull()?.uppercase() ?: "•",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = LifeOsColors.Primary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                merchant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = LifeOsColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val datePart = txn.txnDate.format(DateTimeFormatter.ofPattern("dd MMM"))
            val category = txn.spendCategory?.let(::prettyCategory)
            Text(
                listOfNotNull(datePart, category).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            (if (isCredit) "+" else "") + txn.amount.money(txn.currency),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
    }
}

@Composable
internal fun TransactionDetailDialog(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onEditCategory: () -> Unit,
    onMarkAsRepaid: () -> Unit,
    onMarkAsRefunded: () -> Unit,
    /** Where Places says the user was when paying; null when Places is not available. */
    placeFor: (suspend (Transaction) -> String?)? = null,
) {
    val place by produceState<String?>(null, transaction.id) { value = placeFor?.invoke(transaction) }
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
                place?.let {
                    Text("Where: $it", style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnBackground)
                }
                Text(
                    "Category: ${prettyCategory(transaction.spendCategory ?: "excluded")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Text(
                    "For a merchant refund, you can exclude this purchase even if no bank credit alert reached LifeOS. For money returned by a person, pair both payments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onEditCategory) { Text("Edit category") }
                }
                if (transaction.direction == "debit") {
                    Row {
                        TextButton(onClick = onMarkAsRefunded) { Text("Mark refunded") }
                        TextButton(onClick = onMarkAsRepaid) { Text("Mark as repaid") }
                    }
                }
            }
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
internal fun BudgetEditDialog(
    category: String,
    existingLimit: BigDecimal?,
    saving: Boolean,
    error: String?,
    onSave: (BigDecimal) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(category) {
        mutableStateOf(existingLimit?.setScale(0, RoundingMode.HALF_UP)?.toPlainString() ?: "")
    }
    val parsed = text.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budget for ${prettyCategory(category)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Monthly limit. LifeOS tracks pace against this from cached transactions, offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Monthly limit (₹)") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = !saving && parsed != null) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (existingLimit != null) {
                    TextButton(onClick = onRemove, enabled = !saving) {
                        Text("Remove", color = LifeOsColors.Warning)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun RefundDialog(
    debit: Transaction,
    saving: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark this purchase as refunded?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${debit.amount.money(debit.currency)} at " +
                        (debit.counterparty?.takeIf { it.isNotBlank() } ?: "this merchant"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "The original bank record will stay in LifeOS, but it will no longer count toward spend. Use this only after the merchant has confirmed the refund.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning)
                }
            }
        },
        confirmButton = {
            HoldToConfirmButton(
                enabled = true,
                saving = saving,
                semanticsLabel = "Confirm merchant refund",
                onConfirm = onConfirm,
            )
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
                semanticsLabel = "Confirm repayment",
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
    semanticsLabel: String,
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
                onLongClick(label = semanticsLabel) {
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
