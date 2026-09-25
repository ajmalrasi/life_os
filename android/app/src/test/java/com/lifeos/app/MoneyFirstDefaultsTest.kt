package com.lifeos.app

import com.lifeos.app.presentation.DEFAULT_DESTINATION_ROUTE
import com.lifeos.app.presentation.money.MoneySection
import com.lifeos.app.presentation.money.MoneyUiState
import com.lifeos.app.presentation.money.TransactionSort
import com.lifeos.app.presentation.money.sortTransactions
import com.lifeos.app.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class MoneyFirstDefaultsTest {

    @Test
    fun today_is_the_app_default_and_money_opens_on_overview() {
        assertEquals("dashboard", DEFAULT_DESTINATION_ROUTE)
        assertEquals(MoneySection.OVERVIEW, MoneyUiState().section)
        assertEquals(TransactionSort.RECENT, MoneyUiState().transactionSort)
    }

    @Test
    fun transactions_sort_globally_by_date_or_amount() {
        val olderHigh = transaction(1, "2026-08-01", "900")
        val newerLow = transaction(2, "2026-08-20", "100")
        val newestMid = transaction(3, "2026-08-21", "500")
        val transactions = listOf(olderHigh, newerLow, newestMid)

        assertEquals(listOf(3, 2, 1), sortTransactions(transactions, TransactionSort.RECENT).map { it.id })
        assertEquals(listOf(1, 2, 3), sortTransactions(transactions, TransactionSort.OLDEST).map { it.id })
        assertEquals(listOf(1, 3, 2), sortTransactions(transactions, TransactionSort.HIGHEST).map { it.id })
        assertEquals(listOf(2, 3, 1), sortTransactions(transactions, TransactionSort.LOWEST).map { it.id })
    }

    private fun transaction(id: Int, date: String, amount: String) = Transaction(
        id = id,
        txnDate = LocalDate.parse(date),
        txnAt = null,
        amount = BigDecimal(amount),
        currency = "INR",
        direction = "debit",
        counterparty = "Merchant $id",
        category = null,
        spendCategory = "other",
        account = "HDFC 1234",
        source = "upi",
        reference = null,
    )
}
