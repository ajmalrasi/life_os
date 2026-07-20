package com.lifeos.app.presentation.money

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                onBack = onBack,
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
    onBack: () -> Unit,
    onTogglePaid: () -> Unit,
) {
    val statement = details.statement
    val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy")
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
                                    "${part.label} · ${part.count}",
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${details.items.size} items",
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                )
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
            itemsIndexed(details.items, key = { _, item -> item.id }) { index, item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (index == 0 || details.items[index - 1].txnDate != item.txnDate) {
                        Text(
                            item.txnDate.format(dateFormat),
                            style = MaterialTheme.typography.labelMedium,
                            color = LifeOsColors.OnSurfaceMuted,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    StatementItemRow(item)
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
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
                Button(onClick = {
                    confirmPaid = false
                    onTogglePaid()
                }) { Text("Yes, paid") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmPaid = false }) { Text("Cancel") }
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
            ).joinToString(" – ")
            if (period.isNotBlank()) {
                Text("Statement period  $period", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            }
            if (statement.status == "paid") {
                OutlinedButton(onClick = onTogglePaid, enabled = !savingPaid, modifier = Modifier.fillMaxWidth()) {
                    Text(if (savingPaid) "Saving…" else "Restore as unpaid")
                }
            } else {
                Button(onClick = { confirmPaid = true }, enabled = !savingPaid, modifier = Modifier.fillMaxWidth()) {
                    Text(if (savingPaid) "Saving…" else "Mark as paid")
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
private fun StatementItemRow(item: CardStatementItem) {
    val credit = item.direction == "credit"
    Surface(shape = RoundedCornerShape(14.dp), color = LifeOsColors.Surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.itemType.prettyItemType(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                (if (credit) -item.amount else item.amount).rupees(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (credit) LifeOsColors.Positive else LifeOsColors.OnBackground,
            )
        }
    }
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
