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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.CardStatementDetails
import com.lifeos.app.domain.model.CardStatementItem
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.theme.LifeOsColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

private enum class StatementSortKey { DATE, AMOUNT }

@Composable
fun StatementDetailsScreen(
    onBack: () -> Unit,
    viewModel: StatementDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        when {
            state.loading && state.details == null -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.details != null -> StatementDetailsContent(
                details = state.details!!,
                error = state.error,
                savingPaid = state.savingPaid,
                onTogglePaid = viewModel::togglePaid,
            )
            else -> StatementLoadError(
                message = state.error ?: "This statement isn't available.",
                onBack = onBack,
                onRetry = viewModel::load,
            )
        }
    }
}

@Composable
private fun StatementDetailsContent(
    details: CardStatementDetails,
    error: String?,
    savingPaid: Boolean,
    onTogglePaid: () -> Unit,
) {
    val statement = details.statement
    val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy")
    var selectedItem by remember { mutableStateOf<CardStatementItem?>(null) }
    var sortKey by remember { mutableStateOf(StatementSortKey.DATE) }
    var descending by remember { mutableStateOf(true) }
    val sortedItems = remember(details.items, sortKey, descending) {
        val ascending = when (sortKey) {
            StatementSortKey.DATE -> details.items.sortedWith(
                compareBy<CardStatementItem> { it.txnDate }
                    .thenBy { it.txnTime }
                    .thenBy { it.sequence }
            )
            StatementSortKey.AMOUNT -> details.items.sortedBy { it.amount }
        }
        if (descending) ascending.asReversed() else ascending
    }
    selectedItem?.let { item ->
        StatementItemDetailDialog(item = item, onDismiss = { selectedItem = null })
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
                Column {
                    Text("Card statement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        statement.cardLabel + (statement.cardLast4?.let { " ····$it" } ?: ""),
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
            StatementSummaryCard(statement, savingPaid, onTogglePaid, dateFormat)
        }

        if (details.breakdown.isNotEmpty()) {
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Statement activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        details.breakdown.forEach { part ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    part.label + if (part.count > 0) " · ${part.count}" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LifeOsColors.OnSurfaceMuted,
                                )
                                Text(
                                    part.total.rupees(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (part.total.signum() < 0) LifeOsColors.Positive else LifeOsColors.OnBackground,
                                )
                            }
                        }
                        Text(
                            "Statement activity explains this bill only and is not added to Money spend.",
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            }
        }

        item {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${details.items.size} ${if (details.items.size == 1) "item" else "items"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
                if (details.items.size > 1) {
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatementSortChip(
                            label = if (sortKey == StatementSortKey.DATE && !descending) "Oldest" else "Recent",
                            active = sortKey == StatementSortKey.DATE,
                            descending = descending,
                            onClick = {
                                if (sortKey == StatementSortKey.DATE) descending = !descending
                                else {
                                    sortKey = StatementSortKey.DATE
                                    descending = true
                                }
                            },
                        )
                        StatementSortChip(
                            label = if (sortKey == StatementSortKey.AMOUNT && !descending) "Lowest" else "Highest",
                            active = sortKey == StatementSortKey.AMOUNT,
                            descending = descending,
                            onClick = {
                                if (sortKey == StatementSortKey.AMOUNT) descending = !descending
                                else {
                                    sortKey = StatementSortKey.AMOUNT
                                    descending = true
                                }
                            },
                        )
                    }
                }
            }
        }

        if (details.items.isEmpty()) {
            item {
                LifeOsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Details not available yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (statement.detailsStatus == "pending")
                                "The saved statement will be reparsed during the next Sync everything run."
                            else "The bill summary is available, but no transaction rows could be read from this PDF.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sortedItems.forEach { item ->
                        StatementItemRow(item, onClick = { selectedItem = item })
                    }
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun StatementSortChip(
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
private fun StatementSummaryCard(
    statement: CardStatement,
    savingPaid: Boolean,
    onTogglePaid: () -> Unit,
    dateFormat: DateTimeFormatter,
) {
    val statusColor = when (statement.status) {
        "paid" -> LifeOsColors.Positive
        "overdue" -> LifeOsColors.Warning
        else -> LifeOsColors.Primary
    }
    var confirmPaid by remember(statement.id) { mutableStateOf(false) }
    if (confirmPaid) {
        AlertDialog(
            onDismissRequest = { confirmPaid = false },
            title = { Text("Mark this statement paid?") },
            text = {
                Text(
                    "${statement.cardLabel} · ${(statement.totalDue ?: BigDecimal.ZERO).rupees()} will be removed from upcoming dues. The statement and its details will stay in LifeOS."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPaid = false
                    onTogglePaid()
                }) { Text("Yes, paid") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPaid = false }) { Text("Cancel") }
            },
        )
    }
    LifeOsCard(modifier = Modifier.fillMaxWidth(), accent = statusColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Text("Total due", style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted)
                    Text(
                        (statement.totalDue ?: BigDecimal.ZERO).rupees(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(shape = RoundedCornerShape(50), color = statusColor.copy(alpha = 0.14f)) {
                    Text(
                        statement.status.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryDatum("Due date", statement.dueDate?.format(dateFormat) ?: "Not listed")
                SummaryDatum("Minimum due", statement.minDue?.rupees() ?: "Not listed", end = true)
            }
            val period = listOfNotNull(
                statement.periodStart?.format(dateFormat),
                statement.periodEnd?.format(dateFormat),
            ).joinToString(" to ")
            if (period.isNotBlank()) {
                Text("Statement period  $period", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            }
            HorizontalDivider(thickness = 0.5.dp, color = LifeOsColors.SurfaceBorder)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Payment status",
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                    Text(
                        if (statement.status == "paid") "Marked paid" else "Not marked paid",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (statement.status == "paid") LifeOsColors.Positive else LifeOsColors.OnBackground,
                    )
                }
                TextButton(
                    onClick = if (statement.status == "paid") onTogglePaid else ({ confirmPaid = true }),
                    enabled = !savingPaid,
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        when {
                            savingPaid -> "Saving…"
                            statement.status == "paid" -> "Mark unpaid"
                            else -> "Mark paid"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryDatum(label: String, value: String, end: Boolean = false) {
    Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatementItemRow(item: CardStatementItem, onClick: () -> Unit) {
    val credit = item.direction == "credit"
    val time = item.txnTime?.format(DateTimeFormatter.ofPattern("h:mm a"))
    val metadata = listOfNotNull(
        item.txnDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
        time,
        item.itemType.prettyItemType(),
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
                item.description,
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
            (if (credit) -item.amount else item.amount).rupees(),
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
private fun StatementItemDetailDialog(item: CardStatementItem, onDismiss: () -> Unit) {
    val time = item.txnTime?.format(DateTimeFormatter.ofPattern("h:mm a"))
    val whenText = listOfNotNull(
        item.txnDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
        time,
    ).joinToString(", ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Statement transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.description, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (item.direction == "credit") -item.amount else item.amount).rupees(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (item.direction == "credit") LifeOsColors.Positive else LifeOsColors.OnBackground,
                )
                Text(
                    "$whenText · ${item.itemType.prettyItemType()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                Text(
                    "Statement detail only. This row is not added to Money spend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun StatementLoadError(message: String, onBack: () -> Unit, onRetry: () -> Unit) {
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

private fun String.prettyItemType(): String = when (this) {
    "fee_interest" -> "Interest or fee"
    "cash_advance" -> "Cash advance"
    "emi" -> "EMI"
    "payment" -> "Payment"
    "credit" -> "Credit or refund"
    else -> "Purchase"
}

private fun BigDecimal.rupees(): String {
    val plain = setScale(0, RoundingMode.HALF_UP).toPlainString()
    val negative = plain.startsWith("-")
    val digits = if (negative) plain.drop(1) else plain
    val grouped = if (digits.length <= 3) digits else {
        val tail = digits.takeLast(3)
        val head = digits.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
    }
    return (if (negative) "-₹" else "₹") + grouped
}
