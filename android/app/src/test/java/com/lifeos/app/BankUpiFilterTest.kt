package com.lifeos.app

import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.money.bankUpiAccountKey
import com.lifeos.app.presentation.money.bankUpiAccountOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BankUpiFilterTest {

    @Test
    fun `discovers savings accounts and excludes credit card upi`() {
        val transactions = listOf(
            transaction(1, "HDFC Savings ..7672"),
            transaction(2, "Axis Bank Ltd. Savings ..4402"),
            transaction(3, "HDFC RuPay CC ..5473"),
            transaction(4, "ICICI Card ..3006"),
        )

        val options = bankUpiAccountOptions(transactions)

        assertEquals(listOf("axis:4402", "hdfc:7672"), options.map { it.key })
        assertEquals(listOf("Axis ····4402", "HDFC ····7672"), options.map { it.label })
    }

    @Test
    fun `legacy label drift resolves only through proven savings identity`() {
        val savings = transaction(1, "HDFC Savings ..7672")
        val legacy = transaction(2, "HDFC Bank Card ..7672")
        val creditCard = transaction(3, "HDFC RuPay CC ..5473")
        val known = bankUpiAccountOptions(listOf(savings, legacy, creditCard)).map { it.key }.toSet()

        assertEquals("hdfc:7672", savings.bankUpiAccountKey(known))
        assertEquals("hdfc:7672", legacy.bankUpiAccountKey(known))
        assertNull(creditCard.bankUpiAccountKey(known))
        assertNull(transaction(4, null).bankUpiAccountKey(known))
        assertNull(transaction(5, "HDFC Savings ..7672", source = "card_live").bankUpiAccountKey(known))
    }

    private fun transaction(
        id: Int,
        account: String?,
        source: String = "upi",
    ) = Transaction(
        id = id,
        txnDate = LocalDate.of(2026, 7, 1),
        txnAt = null,
        amount = BigDecimal("100.00"),
        currency = "INR",
        direction = "debit",
        counterparty = "Merchant",
        category = null,
        spendCategory = "other",
        account = account,
        source = source,
        reference = null,
    )
}
