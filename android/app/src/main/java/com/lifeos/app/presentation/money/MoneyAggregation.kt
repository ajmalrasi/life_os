package com.lifeos.app.presentation.money

import com.lifeos.app.domain.model.Transaction
import java.math.BigDecimal
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
