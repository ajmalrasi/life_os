package com.lifeos.app.presentation.money

import com.lifeos.app.domain.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure period-slicing and category/total aggregation for the Money screen.
 *
 * Extracted out of the `remember { }` blocks in [MoneyScreen] so the date-range
 * math and the spend rollups can be unit-tested directly, without a Compose
 * test harness. Nothing here touches [MoneyViewModel] state or Compose runtime
 * types — every function is a plain transform over [Transaction] lists.
 */

/** An inclusive [start, end] calendar-day range for one selected [SpendPeriod]. */
internal data class PeriodSlice(val start: LocalDate, val end: LocalDate)

internal data class CategoryTotal(val category: String, val total: BigDecimal, val count: Int)

/** The visible date range for [period], anchored on [anchor] (today unless the
 * user paged), or the explicit [customStart]/[customEnd] pair for [SpendPeriod.CUSTOM]. */
internal fun sliceFor(
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

/** The immediately-preceding period of the same length/shape as [current], for
 * "vs last period" comparisons. */
internal fun previousPeriodSlice(period: SpendPeriod, current: PeriodSlice): PeriodSlice? = when (period) {
    SpendPeriod.DAY -> PeriodSlice(current.start.minusDays(1), current.end.minusDays(1))
    SpendPeriod.WEEK -> PeriodSlice(current.start.minusWeeks(1), current.end.minusWeeks(1))
    SpendPeriod.MONTH -> current.start.minusMonths(1).let { start ->
        PeriodSlice(start, start.withDayOfMonth(start.lengthOfMonth()))
    }
    SpendPeriod.SIX_MONTHS -> PeriodSlice(current.start.minusMonths(6), current.start.minusDays(1))
    SpendPeriod.YEAR -> PeriodSlice(
        LocalDate.of(current.start.year - 1, 1, 1),
        LocalDate.of(current.start.year - 1, 12, 31),
    )
    SpendPeriod.CUSTOM -> {
        val days = ChronoUnit.DAYS.between(current.start, current.end) + 1
        PeriodSlice(current.start.minusDays(days), current.end.minusDays(days))
    }
}

/** True when [date] falls within this inclusive slice. */
internal fun PeriodSlice.contains(date: LocalDate): Boolean =
    !date.isBefore(start) && !date.isAfter(end)

/** Debits filtered down to this slice's date range. */
internal fun List<Transaction>.inSlice(slice: PeriodSlice): List<Transaction> =
    filter { slice.contains(it.txnDate) }

/** Sum of [Transaction.amount] over the list; zero for an empty list. */
internal fun List<Transaction>.totalAmount(): BigDecimal =
    fold(BigDecimal.ZERO) { acc, txn -> acc + txn.amount }

/** Per-category totals (each transaction must already have a non-null
 * [Transaction.spendCategory]), sorted highest spend first. */
internal fun categoryTotals(transactions: List<Transaction>): List<CategoryTotal> =
    transactions.groupBy { it.spendCategory!! }
        .map { (category, list) -> CategoryTotal(category, list.totalAmount(), list.size) }
        .sortedByDescending { it.total }

/** One category's monthly budget pace: spent-so-far vs. what that projects to
 * by month-end at today's straight-line daily rate. Mirrors the backend's
 * `app/services/budgets.py::budget_pace_summary` formula exactly (same
 * `spent / days_elapsed * days_in_month` projection) so Ask and the on-device
 * Overview bars never disagree — this just runs offline, from the cached
 * transaction feed, instead of a live query. */
internal data class BudgetPace(
    val category: String,
    val monthlyLimit: BigDecimal,
    val spentSoFar: BigDecimal,
    val projectedMonthTotal: BigDecimal,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val onPaceToExceed: Boolean,
    /** Calendar day (1..daysInMonth) the straight-line pace would cross the
     * limit, if it does this month; null when spending never catches up. */
    val projectedExceedDay: Int?,
)

/** [transactions] should be the same debit/spend-category-non-null population
 * as [categoryTotals] draws from (i.e. `spendableTransactions`, unfiltered by
 * the UI's period/source/EMI toggles — a budget tracks the whole month
 * regardless of what the user happens to be viewing). */
internal fun budgetPace(
    category: String,
    monthlyLimit: BigDecimal,
    transactions: List<Transaction>,
    today: LocalDate = LocalDate.now(),
): BudgetPace {
    val monthSlice = PeriodSlice(today.withDayOfMonth(1), today)
    val daysInMonth = today.lengthOfMonth()
    val daysElapsed = today.dayOfMonth

    val spent = transactions
        .filter { it.spendCategory == category }
        .inSlice(monthSlice)
        .totalAmount()

    val dailyRate = spent.divide(BigDecimal(daysElapsed), 6, RoundingMode.HALF_UP)
    val projected = dailyRate.multiply(BigDecimal(daysInMonth)).setScale(2, RoundingMode.HALF_UP)

    val exceedDay = if (dailyRate > BigDecimal.ZERO && monthlyLimit > BigDecimal.ZERO) {
        val day = monthlyLimit.divide(dailyRate, 0, RoundingMode.CEILING).toInt()
        day.takeIf { it in 1..daysInMonth }
    } else {
        null
    }

    return BudgetPace(
        category = category,
        monthlyLimit = monthlyLimit,
        spentSoFar = spent,
        projectedMonthTotal = projected,
        daysElapsed = daysElapsed,
        daysInMonth = daysInMonth,
        onPaceToExceed = projected > monthlyLimit,
        projectedExceedDay = exceedDay,
    )
}
