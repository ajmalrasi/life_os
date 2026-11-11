package com.lifeos.app.presentation.money

import com.lifeos.app.domain.model.Transaction

internal const val ALL_BANK_UPI_FILTER = "__all_bank_upi__"

internal data class BankUpiAccountOption(
    val key: String,
    val label: String,
)

internal data class AccountIdentity(
    val key: String,
    val bank: String,
    val last4: String,
)

private val trailingAccountDigits = Regex("""\.\.\s*(\d+)\s*$""")
internal val savingsAccount = Regex("""\bsavings?\b""", RegexOption.IGNORE_CASE)

/**
 * Finds bank-account UPI identities only from rows that explicitly say
 * "Savings". Card UPI rows are never treated as bank UPI merely because their
 * source is also "upi".
 */
internal fun bankUpiAccountOptions(
    transactions: List<Transaction>,
): List<BankUpiAccountOption> =
    transactions
        .asSequence()
        .filter { it.source.equals("upi", ignoreCase = true) }
        .mapNotNull { transaction ->
            val account = transaction.account ?: return@mapNotNull null
            if (!savingsAccount.containsMatchIn(account)) return@mapNotNull null
            parseAccountIdentity(account)
        }
        .distinctBy { it.key }
        .map { identity ->
            BankUpiAccountOption(
                key = identity.key,
                label = "${identity.bank} ····${identity.last4}",
            )
        }
        .sortedBy { it.label }
        .toList()

/**
 * Resolves a transaction to one of the proven savings-account identities.
 * This also recovers older rows whose label drifted (for example "Card
 * ..7672") when another row proves that the same bank + suffix is a savings
 * account. Unknown or suffix-less UPI rows stay excluded rather than guessed.
 */
internal fun Transaction.bankUpiAccountKey(
    knownSavingsKeys: Set<String>,
): String? {
    if (!source.equals("upi", ignoreCase = true)) return null
    val identity = account?.let(::parseAccountIdentity) ?: return null
    return identity.key.takeIf { it in knownSavingsKeys }
}

internal fun parseAccountIdentity(account: String): AccountIdentity? {
    val last4 = trailingAccountDigits.find(account)?.groupValues?.get(1) ?: return null
    val bankToken = account.trim().substringBefore(' ').lowercase()
    if (bankToken.isBlank()) return null
    val bank = when (bankToken) {
        "hdfc", "icici", "sbi" -> bankToken.uppercase()
        else -> bankToken.replaceFirstChar { it.uppercase() }
    }
    return AccountIdentity(
        key = "$bankToken:$last4",
        bank = bank,
        last4 = last4,
    )
}
