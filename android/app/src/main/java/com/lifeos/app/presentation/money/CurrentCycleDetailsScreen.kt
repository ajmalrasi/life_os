package com.lifeos.app.presentation.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.CurrentCardCycleDetails
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.theme.LifeOsColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class CycleSortKey { DATE, AMOUNT }

@Composable
fun CurrentCycleDetailsScreen(
    onBack: () -> Unit,
    viewModel: CurrentCycleDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        when {
            state.loading && state.details == null -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.details != null -> {
                CurrentCycleDetailsContent(
                    details = state.details!!,
                    error = state.error,
                    onBack = onBack,
                )
            }
            else -> {
                CurrentCycleLoadError(
                    message = state.error ?: "This billing cycle isn't available.",
                    onBack = onBack,
                    onRetry = viewModel::load,
                )
            }
        }
    }
}

@Composable
private fun CurrentCycleDetailsContent(
    details: CurrentCardCycleDetails,
    error: String?,
    onBack: () -> Unit,
) {
    val cycle = details.cycle
    val fullDate = DateTimeFormatter.ofPattern("dd MMM yyyy")
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var sortKey by remember { mutableStateOf(CycleSortKey.DATE) }
    var descending by remember { mutableStateOf(true) }
    val sortedTransactions = remember(details.transactions, sortKey, descending) {
        val ascending = when (sortKey) {
            CycleSortKey.DATE -> details.transactions.sortedWith(
                compareBy<Transaction> { it.txnDate }.thenBy { it.txnAt }.thenBy { it.id }
            )
            CycleSortKey.AMOUNT -> details.transactions.sortedBy { it.amount }
        }
        if (descending) ascending.asReversed() else ascending
    }
    val categoryBreakdown = remember(details.transactions) {
        details.transactions
            .filter { it.direction == "debit" }
            .groupBy { it.spendCategory ?: "uncategorized" }
            .mapValues { (_, transactions) ->
                transactions.fold(BigDecimal.ZERO) { total, transaction -> total + transaction.amount }
            }
            .toList()
            .sortedByDescending { it.second }
    }

    selectedTransaction?.let { transaction ->
        CycleTransactionDialog(
            transaction = transaction,
            onDismiss = { selectedTransaction = null },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Money")
                }
                Column {
                    Text(
                        "Current billing cycle",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        cycle.displayCardLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
        }

        error?.let { message ->
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Warning) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning)
                }
            }
        }

        item {
            CurrentCycleSummaryCard(cycle = cycle, dateFormat = fullDate)
        }

        if (categoryBreakdown.isNotEmpty() || cycle.refunds > BigDecimal.ZERO) {
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Cycle breakdown",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        categoryBreakdown.forEach { (category, total) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    category.prettyCategory(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LifeOsColors.OnSurfaceMuted,
                                )
                                Text(
                                    total.rupees(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (cycle.refunds > BigDecimal.ZERO) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Refunds and credits",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LifeOsColors.OnSurfaceMuted,
                                )
                                Text(
                                    (-cycle.refunds).rupees(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = LifeOsColors.Positive,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        details.transactions.size.itemCountLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
                if (details.transactions.size > 1) {
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CycleSortChip(
                            label = if (sortKey == CycleSortKey.DATE && !descending) "Oldest" else "Recent",
                            active = sortKey == CycleSortKey.DATE,
                            descending = descending,
                            onClick = {
                                if (sortKey == CycleSortKey.DATE) {
                                    descending = !descending
                                } else {
                                    sortKey = CycleSortKey.DATE
                                    descending = true
                                }
                            },
                        )
                        CycleSortChip(
                            label = if (sortKey == CycleSortKey.AMOUNT && !descending) "Lowest" else "Highest",
                            active = sortKey == CycleSortKey.AMOUNT,
                            descending = descending,
                            onClick = {
                                if (sortKey == CycleSortKey.AMOUNT) {
                                    descending = !descending
                                } else {
                                    sortKey = CycleSortKey.AMOUNT
                                    descending = true
                                }
                            },
                        )
                    }
                }
            }
        }

        if (details.transactions.isEmpty()) {
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "No recorded spending yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "No live card transactions have been recorded in this exact billing cycle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sortedTransactions.forEach { transaction ->
                        CycleTransactionRow(
                            transaction = transaction,
                            onClick = { selectedTransaction = transaction },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun CurrentCycleSummaryCard(
    cycle: CurrentCardCycle,
    dateFormat: DateTimeFormatter,
) {
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = LifeOsColors.Primary) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    "Current cycle spend",
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Text(
                    cycle.currentSpend.rupees(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CycleSummaryDatum("Cycle starts", cycle.cycleStart.format(dateFormat))
                CycleSummaryDatum("Billing date", cycle.cycleEnd.format(dateFormat), end = true)
            }
            HorizontalDivider(thickness = 0.5.dp, color = LifeOsColors.SurfaceBorder)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CycleSummaryDatum("Recorded transactions", cycle.transactionCount.toString())
                CycleSummaryDatum(
                    "Net after refunds",
                    cycle.netCycleAmount.rupees(),
                    end = true,
                )
            }
            Text(
                "This is the exact recorded card spending from ${cycle.cycleStart.format(dateFormat)} through today. No future charges or EMI estimates are included.",
                style = MaterialTheme.typography.labelSmall,
                color = LifeOsColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun CycleSummaryDatum(label: String, value: String, end: Boolean = false) {
    Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CycleSortChip(
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
            .background(
                if (active) LifeOsColors.PrimarySoft else LifeOsColors.SurfaceMuted,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun CycleTransactionRow(transaction: Transaction, onClick: () -> Unit) {
    val credit = transaction.direction == "credit"
    val date = transaction.txnDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    val metadata = listOfNotNull(
        date,
        transaction.spendCategory?.prettyCategory(),
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                transaction.displayDescription(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                metadata,
                style = MaterialTheme.typography.labelSmall,
                color = LifeOsColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.widthIn(min = 12.dp))
        Text(
            (if (credit) -transaction.amount else transaction.amount).rupees(),
            modifier = Modifier.widthIn(min = 92.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (credit) LifeOsColors.Positive else LifeOsColors.OnBackground,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun CycleTransactionDialog(transaction: Transaction, onDismiss: () -> Unit) {
    val whenText = buildList {
        add(transaction.txnDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
        transaction.txnAt?.atZone(ZoneId.systemDefault())?.let {
            add(it.format(DateTimeFormatter.ofPattern("h:mm a")))
        }
    }.joinToString(", ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Current cycle transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    transaction.displayDescription(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    (if (transaction.direction == "credit") -transaction.amount else transaction.amount).rupees(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (transaction.direction == "credit") {
                        LifeOsColors.Positive
                    } else {
                        LifeOsColors.OnBackground
                    },
                )
                Text(
                    listOfNotNull(whenText, transaction.spendCategory?.prettyCategory())
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                transaction.account?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                }
                Text(
                    "This live transaction is included in the current cycle total.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun CurrentCycleLoadError(message: String, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnSurfaceMuted)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

private fun CurrentCardCycle.displayCardLabel(): String {
    val withoutSuffix = cardLabel
        .replace(Regex("""\s*[·.]{2,}\s*\d+\s*$"""), "")
        .trim()
    return "$withoutSuffix ····$cardLast4"
}

private fun Transaction.displayDescription(): String =
    counterparty?.takeIf { it.isNotBlank() }
        ?: reference?.takeIf { it.isNotBlank() }
        ?: "Card transaction"

private fun String.prettyCategory(): String =
    replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun Int.itemCountLabel(): String = "$this ${if (this == 1) "item" else "items"}"

private fun BigDecimal.rupees(): String {
    val plain = setScale(0, RoundingMode.HALF_UP).toPlainString()
    val negative = plain.startsWith("-")
    val digits = if (negative) plain.drop(1) else plain
    val grouped = if (digits.length <= 3) {
        digits
    } else {
        val tail = digits.takeLast(3)
        val head = digits.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
    }
    return (if (negative) "-₹" else "₹") + grouped
}
