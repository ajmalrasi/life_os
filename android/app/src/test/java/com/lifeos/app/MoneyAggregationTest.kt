package com.lifeos.app

import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.money.SpendPeriod
import com.lifeos.app.presentation.money.bankUpiAccountKey
import com.lifeos.app.presentation.money.bankUpiAccountOptions
import com.lifeos.app.presentation.money.budgetPace
import com.lifeos.app.presentation.money.categoryTotals
import com.lifeos.app.presentation.money.filterEmiTransactions
import com.lifeos.app.presentation.money.inSlice
import com.lifeos.app.presentation.money.previousPeriodSlice
import com.lifeos.app.presentation.money.sliceFor
import com.lifeos.app.presentation.money.totalAmount
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class MoneyAggregationTest {

    // --- sliceFor: period boundaries -----------------------------------------

    @Test
    fun `day slice is the anchor date alone`() {
        val anchor = LocalDate.of(2026, 8, 12)
        val slice = sliceFor(SpendPeriod.DAY, anchor, null, null)
        assertEquals(anchor, slice.start)
        assertEquals(anchor, slice.end)
    }

    @Test
    fun `week slice starts on Monday and spans seven days`() {
        // 2026-08-12 is a Wednesday.
        val slice = sliceFor(SpendPeriod.WEEK, LocalDate.of(2026, 8, 12), null, null)
        assertEquals(LocalDate.of(2026, 8, 10), slice.start) // Monday
        assertEquals(LocalDate.of(2026, 8, 16), slice.end)   // Sunday
    }

    @Test
    fun `month slice covers the full calendar month`() {
        val slice = sliceFor(SpendPeriod.MONTH, LocalDate.of(2026, 2, 15), null, null)
        assertEquals(LocalDate.of(2026, 2, 1), slice.start)
        assertEquals(LocalDate.of(2026, 2, 28), slice.end) // 2026 is not a leap year
    }

    @Test
    fun `six month slice ends with the anchor month and spans six calendar months`() {
        val slice = sliceFor(SpendPeriod.SIX_MONTHS, LocalDate.of(2026, 8, 12), null, null)
        assertEquals(LocalDate.of(2026, 3, 1), slice.start)
        assertEquals(LocalDate.of(2026, 8, 31), slice.end)
    }

    @Test
    fun `year slice covers Jan 1 to Dec 31`() {
        val slice = sliceFor(SpendPeriod.YEAR, LocalDate.of(2026, 8, 12), null, null)
        assertEquals(LocalDate.of(2026, 1, 1), slice.start)
        assertEquals(LocalDate.of(2026, 12, 31), slice.end)
    }

    @Test
    fun `custom slice uses explicit bounds, falling back to the anchor month when unset`() {
        val explicit = sliceFor(
            SpendPeriod.CUSTOM,
            LocalDate.of(2026, 8, 12),
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 20),
        )
        assertEquals(LocalDate.of(2026, 7, 1), explicit.start)
        assertEquals(LocalDate.of(2026, 7, 20), explicit.end)

        val fallback = sliceFor(SpendPeriod.CUSTOM, LocalDate.of(2026, 8, 12), null, null)
        assertEquals(LocalDate.of(2026, 8, 1), fallback.start)
        assertEquals(LocalDate.of(2026, 8, 31), fallback.end)
    }

    // --- previousPeriodSlice: prior-period comparison -------------------------

    @Test
    fun `previous day is one day earlier`() {
        val current = sliceFor(SpendPeriod.DAY, LocalDate.of(2026, 8, 12), null, null)
        val previous = previousPeriodSlice(SpendPeriod.DAY, current)
        assertEquals(LocalDate.of(2026, 8, 11), previous?.start)
        assertEquals(LocalDate.of(2026, 8, 11), previous?.end)
    }

    @Test
    fun `previous month keeps its own full length, not the current month's day count`() {
        // March (31 days) vs February (28 days in 2026).
        val current = sliceFor(SpendPeriod.MONTH, LocalDate.of(2026, 3, 15), null, null)
        val previous = previousPeriodSlice(SpendPeriod.MONTH, current)
        assertEquals(LocalDate.of(2026, 2, 1), previous?.start)
        assertEquals(LocalDate.of(2026, 2, 28), previous?.end)
    }

    @Test
    fun `previous year is the prior calendar year`() {
        val current = sliceFor(SpendPeriod.YEAR, LocalDate.of(2026, 8, 12), null, null)
        val previous = previousPeriodSlice(SpendPeriod.YEAR, current)
        assertEquals(LocalDate.of(2025, 1, 1), previous?.start)
        assertEquals(LocalDate.of(2025, 12, 31), previous?.end)
    }

    @Test
    fun `previous custom range is the same length immediately before it`() {
        val current = sliceFor(
            SpendPeriod.CUSTOM,
            LocalDate.of(2026, 8, 12),
            LocalDate.of(2026, 7, 10),
            LocalDate.of(2026, 7, 19), // 10 days inclusive
        )
        val previous = previousPeriodSlice(SpendPeriod.CUSTOM, current)
        assertEquals(LocalDate.of(2026, 6, 30), previous?.start)
        assertEquals(LocalDate.of(2026, 7, 9), previous?.end)
    }

    // --- EMI exclusion + bank UPI filter, applied together --------------------

    @Test
    fun `EMI exclusion and bank UPI filter compose without either masking the other`() {
        val hdfcSavings = "HDFC Savings ..7672"
        val axisSavings = "Axis Bank Ltd. Savings ..4402"
        val transactions = listOf(
            transaction(1, "food", hdfcSavings),
            transaction(2, "emi", hdfcSavings),        // EMI on the filtered-in account
            transaction(3, "food", axisSavings),        // right category, wrong account
            transaction(4, "emi", axisSavings),          // wrong account AND EMI
        )
        val knownKeys = bankUpiAccountOptions(transactions).map { it.key }.toSet()

        val hdfcOnly = transactions.filter { it.bankUpiAccountKey(knownKeys) == "hdfc:7672" }
        val defaultView = filterEmiTransactions(hdfcOnly, includeEmi = false)
        val includeEmiView = filterEmiTransactions(hdfcOnly, includeEmi = true)

        assertEquals(listOf(1), defaultView.map { it.id })
        assertEquals(listOf(1, 2), includeEmiView.map { it.id }.sorted())
    }

    // --- category/total rollups -----------------------------------------------

    @Test
    fun `categoryTotals sums per category and sorts highest spend first`() {
        val account = "HDFC Savings ..7672"
        val transactions = listOf(
            transaction(1, "food", account, amount = "100.00"),
            transaction(2, "food", account, amount = "50.00"),
            transaction(3, "fuel", account, amount = "300.00"),
        )

        val totals = categoryTotals(transactions)

        assertEquals(listOf("fuel", "food"), totals.map { it.category })
        assertEquals(BigDecimal("300.00"), totals[0].total)
        assertEquals(BigDecimal("150.00"), totals[1].total)
        assertEquals(2, totals[1].count)
    }

    @Test
    fun `inSlice and totalAmount filter to the date range and sum the result`() {
        val account = "HDFC Savings ..7672"
        val transactions = listOf(
            transaction(1, "food", account, amount = "10.00", date = LocalDate.of(2026, 7, 31)),
            transaction(2, "food", account, amount = "20.00", date = LocalDate.of(2026, 8, 1)),
            transaction(3, "food", account, amount = "30.00", date = LocalDate.of(2026, 8, 31)),
            transaction(4, "food", account, amount = "40.00", date = LocalDate.of(2026, 9, 1)),
        )
        val august = sliceFor(SpendPeriod.MONTH, LocalDate.of(2026, 8, 15), null, null)

        val inAugust = transactions.inSlice(august)

        assertEquals(listOf(2, 3), inAugust.map { it.id })
        assertEquals(BigDecimal("50.00"), inAugust.totalAmount())
    }

    @Test
    fun `totalAmount of an empty list is zero`() {
        assertEquals(BigDecimal.ZERO, emptyList<Transaction>().totalAmount())
    }

    @Test
    fun `previousPeriodSlice returns a comparison range for every SpendPeriod`() {
        // Guards the prior-period comparison card: it needs a non-null result
        // for every period the picker can select, including SIX_MONTHS/CUSTOM.
        SpendPeriod.entries.forEach { period ->
            val current = sliceFor(period, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10))
            assert(previousPeriodSlice(period, current) != null) { "expected a previous slice for $period" }
        }
    }

    // --- budgetPace ------------------------------------------------------------

    @Test
    fun `budgetPace sums only this month's spend in the category`() {
        val transactions = listOf(
            transaction(1, "food", "acc", amount = "100.00", date = LocalDate.of(2026, 8, 3)),
            transaction(2, "food", "acc", amount = "50.00", date = LocalDate.of(2026, 8, 10)),
            transaction(3, "food", "acc", amount = "999.00", date = LocalDate.of(2026, 7, 31)), // last month
            transaction(4, "fuel", "acc", amount = "999.00", date = LocalDate.of(2026, 8, 5)),  // other category
        )
        val pace = budgetPace("food", BigDecimal("1000.00"), transactions, today = LocalDate.of(2026, 8, 15))

        assertEquals(BigDecimal("150.00"), pace.spentSoFar)
        assertEquals(15, pace.daysElapsed)
        assertEquals(31, pace.daysInMonth)
    }

    @Test
    fun `budgetPace projects a straight line to month end`() {
        // 150 spent over the first 15 days of a 31-day month -> 10/day -> 310 projected.
        val transactions = listOf(
            transaction(1, "food", "acc", amount = "150.00", date = LocalDate.of(2026, 8, 10)),
        )
        val pace = budgetPace("food", BigDecimal("1000.00"), transactions, today = LocalDate.of(2026, 8, 15))

        assertEquals(BigDecimal("310.00"), pace.projectedMonthTotal)
        assertEquals(false, pace.onPaceToExceed)
        assertEquals(null, pace.projectedExceedDay)
    }

    @Test
    fun `budgetPace flags an overspend and the day it's projected to happen`() {
        // 100/day for 5 days against a 300 limit -> exceeds on day 3.
        val transactions = listOf(
            transaction(1, "food", "acc", amount = "500.00", date = LocalDate.of(2026, 8, 3)),
        )
        val pace = budgetPace("food", BigDecimal("300.00"), transactions, today = LocalDate.of(2026, 8, 5))

        assertEquals(true, pace.onPaceToExceed)
        assertEquals(3, pace.projectedExceedDay)
    }

    @Test
    fun `budgetPace with no spend yet projects zero and never exceeds`() {
        val pace = budgetPace("food", BigDecimal("1000.00"), emptyList(), today = LocalDate.of(2026, 8, 1))

        assertEquals(BigDecimal.ZERO, pace.spentSoFar)
        assertEquals(BigDecimal("0.00"), pace.projectedMonthTotal)
        assertEquals(false, pace.onPaceToExceed)
        assertEquals(null, pace.projectedExceedDay)
    }

    private fun transaction(
        id: Int,
        spendCategory: String?,
        account: String?,
        amount: String = "100.00",
        date: LocalDate = LocalDate.of(2026, 8, 3),
        source: String = "upi",
    ) = Transaction(
        id = id,
        txnDate = date,
        txnAt = null,
        amount = BigDecimal(amount),
        currency = "INR",
        direction = "debit",
        counterparty = "Merchant",
        category = null,
        spendCategory = spendCategory,
        account = account,
        source = source,
        reference = null,
    )
}
