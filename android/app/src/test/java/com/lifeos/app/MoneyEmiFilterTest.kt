package com.lifeos.app

import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.money.filterEmiTransactions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class MoneyEmiFilterTest {

    @Test
    fun default_view_excludes_emi_and_emi_fees_but_keeps_them_recorded() {
        val rows = listOf(
            transaction(1, "food"),
            transaction(2, "emi"),
            transaction(3, "emi_fees"),
        )

        assertEquals(listOf(1), filterEmiTransactions(rows, includeEmi = false).map { it.id })
        assertEquals(listOf(1, 2, 3), filterEmiTransactions(rows, includeEmi = true).map { it.id })
    }

    private fun transaction(id: Int, spendCategory: String) = Transaction(
        id = id,
        txnDate = LocalDate.of(2026, 8, 3),
        txnAt = null,
        amount = BigDecimal("100.00"),
        currency = "INR",
        direction = "debit",
        counterparty = "Test",
        category = null,
        spendCategory = spendCategory,
        account = "HDFC Savings ..7672",
        source = "upi",
        reference = null,
    )
}
