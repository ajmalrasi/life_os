package com.lifeos.app

import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.money.SpendPeriod
import com.lifeos.app.presentation.money.spendTrendSeries
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class MonthlySpendChartTest {
    @Test
    fun daily_bars_include_zero_days_and_reconcile_to_the_filtered_month() {
        val series = spendTrendSeries(
            listOf(
                transaction(1, "2026-09-01", "100.25"),
                transaction(2, "2026-09-01", "50.75"),
                transaction(3, "2026-09-30", "200.00"),
                transaction(4, "2026-08-31", "900.00"),
                transaction(5, "2026-10-01", "800.00"),
            ),
            SpendPeriod.MONTH,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30),
        )
        assertEquals(30, series.size)
        assertEquals(BigDecimal("151.00"), series.first().amount)
        assertEquals(BigDecimal.ZERO, series[1].amount)
        assertEquals(BigDecimal("200.00"), series.last().amount)
        assertEquals(BigDecimal("351.00"), series.fold(BigDecimal.ZERO) { sum, day -> sum + day.amount })
        assertEquals(LocalDate.of(2026, 9, 30), series.last().end)
    }

    @Test
    fun empty_and_leap_months_have_the_correct_calendar_days() {
        val leap = spendTrendSeries(
            emptyList(), SpendPeriod.MONTH,
            LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29),
        )
        assertEquals(29, leap.size)
        assertEquals(LocalDate.of(2024, 2, 29), leap.last().end)
        assertEquals(BigDecimal.ZERO, leap.fold(BigDecimal.ZERO) { sum, day -> sum + day.amount })
        assertEquals(
            28,
            spendTrendSeries(emptyList(), SpendPeriod.MONTH, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28)).size,
        )
        assertEquals(
            31,
            spendTrendSeries(emptyList(), SpendPeriod.MONTH, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)).size,
        )
    }

    @Test
    fun six_months_uses_weekly_buckets_and_year_uses_calendar_months() {
        val sixMonths = spendTrendSeries(
            listOf(
                transaction(1, "2026-01-01", "10"),
                transaction(2, "2026-01-07", "20"),
                transaction(3, "2026-01-08", "30"),
                transaction(4, "2026-06-30", "40"),
            ),
            SpendPeriod.SIX_MONTHS,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 6, 30),
        )
        assertEquals(26, sixMonths.size)
        assertEquals(BigDecimal("30"), sixMonths.first().amount)
        assertEquals(BigDecimal("30"), sixMonths[1].amount)
        assertEquals(LocalDate.of(2026, 6, 30), sixMonths.last().end)
        assertEquals(BigDecimal("100"), sixMonths.fold(BigDecimal.ZERO) { sum, bucket -> sum + bucket.amount })

        val year = spendTrendSeries(
            listOf(
                transaction(1, "2026-01-01", "10"),
                transaction(2, "2026-01-31", "20"),
                transaction(3, "2026-02-01", "30"),
                transaction(4, "2026-12-31", "40"),
            ),
            SpendPeriod.YEAR,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
        )
        assertEquals(12, year.size)
        assertEquals(BigDecimal("30"), year.first().amount)
        assertEquals(BigDecimal("30"), year[1].amount)
        assertEquals(BigDecimal("40"), year.last().amount)
    }

    private fun transaction(id: Int, date: String, amount: String) = Transaction(
        id = id, txnDate = LocalDate.parse(date), txnAt = null,
        amount = BigDecimal(amount), currency = "INR", direction = "debit",
        counterparty = "Merchant", category = null, spendCategory = "food",
        account = "HDFC 1234", source = "upi", reference = null,
    )
}
