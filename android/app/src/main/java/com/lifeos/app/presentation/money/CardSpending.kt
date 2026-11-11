package com.lifeos.app.presentation.money

import com.lifeos.app.domain.model.Transaction
import java.math.BigDecimal

internal data class CardSpend(
    val label: String,
    val amount: BigDecimal,
    val count: Int,
)

private val cardLabel = Regex("""\b(?:card|cc)\b""", RegexOption.IGNORE_CASE)
private val debitCard = Regex("""\bdebit card\b""", RegexOption.IGNORE_CASE)
private val maskedSuffix = Regex("""\.\.\s*X+(?=\d)""", RegexOption.IGNORE_CASE)
private val cardSources = setOf("card_live", "hdfc_card_live", "credit_card", "statement_emi")

internal fun cardSpending(
    spending: List<Transaction>,
    history: List<Transaction>,
): List<CardSpend> {
    val savingsKeys = history.asSequence()
        .mapNotNull { it.account }
        .filter { savingsAccount.containsMatchIn(it) }
        .mapNotNull { parseAccountIdentity(it)?.key }
        .toSet() + "hdfc:7672"

    val byCard = spending.filter { it.currency == "INR" }.mapNotNull { txn ->
        val account = txn.account.orEmpty()
        if (savingsAccount.containsMatchIn(account) || debitCard.containsMatchIn(account)) return@mapNotNull null

        val source = txn.source.lowercase()
        val identity = parseAccountIdentity(account.replace(maskedSuffix, ".."))
        if (identity != null && identity.key in savingsKeys && (source == "upi" || identity.last4.length > 4)) {
            return@mapNotNull null
        }
        if (source !in cardSources && !cardLabel.containsMatchIn(account) && identity?.key != "icici:0010") {
            return@mapNotNull null
        }

        // Older statement rows can use the confirmed short suffix for this card.
        val card = if (identity?.key == "sbi:70") parseAccountIdentity("SBI Card ..1970") else identity
        val label = card?.let { "${it.bank.uppercase()} ····${it.last4}" } ?: "Card not identified"
        label to txn
    }.groupBy({ it.first }, { it.second })

    return byCard.map { (label, transactions) ->
        CardSpend(label, transactions.totalAmount(), transactions.size)
    }.sortedWith(compareByDescending<CardSpend> { it.amount }.thenBy { it.label })
}
