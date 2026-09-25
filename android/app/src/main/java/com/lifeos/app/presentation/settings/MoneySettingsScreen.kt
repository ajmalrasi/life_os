package com.lifeos.app.presentation.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.lifeOsFieldColors
import com.lifeos.app.presentation.money.BUDGETABLE_CATEGORIES
import com.lifeos.app.presentation.money.BudgetEditDialog
import com.lifeos.app.presentation.money.prettyCategory
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.theme.LifeOsColors
import com.lifeos.app.presentation.money.BudgetPace
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneySettingsScreen(
    viewModel: MoneySettingsViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val budgetPaces by viewModel.budgetPaces.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val fieldColors = lifeOsFieldColors()

    val context = LocalContext.current
    var notificationPermissionGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        settingsViewModel.setCardDueRemindersEnabled(granted)
    }
    var showCategoryPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader("Money", "Income, budgets and bill reminders")

        SettingsSectionLabel("Income")
        SettingsGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Monthly income", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.incomeText,
                    onValueChange = viewModel::onIncomeChange,
                    label = { Text("Take-home income (₹)") },
                    singleLine = true,
                    enabled = !state.profileLoading && !state.savingProfile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = state.savingsGoalText,
                    onValueChange = viewModel::onSavingsGoalChange,
                    label = { Text("Savings goal % (optional)") },
                    singleLine = true,
                    enabled = !state.profileLoading && !state.savingProfile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                )
                Text(
                    "Used by Insights to judge spending against what you earn. Stored on your LifeOS server only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                state.profileError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning)
                }
                Button(
                    onClick = viewModel::saveProfile,
                    enabled = !state.profileLoading && !state.savingProfile,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LifeOsColors.Primary,
                        contentColor = LifeOsColors.OnPrimary,
                    ),
                ) {
                    Text(if (state.profileSaved) "Saved" else if (state.savingProfile) "Saving…" else "Save")
                }
            }
        }

        SettingsSectionLabel("Budgets")
        SettingsGroup {
            Column {
                if (budgets.isEmpty()) {
                    Text(
                        "No budgets set yet.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                } else {
                    budgets.forEachIndexed { index, budget ->
                        if (index > 0) HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.beginBudgetEdit(budget.category) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val pace = budgetPaces[budget.category]
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(prettyCategory(budget.category), style = MaterialTheme.typography.bodyMedium)
                                if (pace != null) {
                                    Text(
                                        budgetPaceLine(pace),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (pace.onPaceToExceed) LifeOsColors.Warning else LifeOsColors.OnSurfaceMuted,
                                    )
                                }
                            }
                            Text(
                                "${budget.monthlyLimit.rupees()}/mo",
                                style = MaterialTheme.typography.labelMedium,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                        }
                    }
                }
                HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                TextButton(
                    onClick = { showCategoryPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Add budget")
                }
            }
        }

        SettingsSectionLabel("Reminders")
        SettingsGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Card due reminders", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Notifies 3 days and 1 day before an unpaid statement is due.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
                Switch(
                    checked = settingsState.cardDueRemindersEnabled && notificationPermissionGranted,
                    onCheckedChange = { enabled ->
                        when {
                            !enabled -> settingsViewModel.setCardDueRemindersEnabled(false)
                            notificationPermissionGranted -> settingsViewModel.setCardDueRemindersEnabled(true)
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            else -> settingsViewModel.setCardDueRemindersEnabled(true)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showCategoryPicker) {
        val available = BUDGETABLE_CATEGORIES.filterNot { key -> budgets.any { it.category == key } }
        ModalBottomSheet(onDismissRequest = { showCategoryPicker = false }, containerColor = LifeOsColors.SurfaceElevated) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Add a budget", style = MaterialTheme.typography.headlineMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(available) { category ->
                        Text(
                            prettyCategory(category),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showCategoryPicker = false
                                    viewModel.beginBudgetEdit(category)
                                }
                                .padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    state.budgetEditCategory?.let { category ->
        BudgetEditDialog(
            category = category,
            existingLimit = budgets.firstOrNull { it.category == category }?.monthlyLimit,
            saving = state.budgetSaving,
            error = state.budgetError,
            onSave = viewModel::saveBudget,
            onRemove = viewModel::removeBudget,
            onDismiss = viewModel::dismissBudgetEdit,
        )
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun budgetPaceLine(pace: BudgetPace): String {
    val spent = "${pace.spentSoFar.rupees()} spent"
    return when {
        pace.spentSoFar > pace.monthlyLimit -> "$spent, over the limit"
        pace.onPaceToExceed -> pace.projectedExceedDay?.let { "$spent, on pace to exceed by day $it" }
            ?: "$spent, on pace to exceed"
        else -> "$spent, on track for ${pace.projectedMonthTotal.rupees()}"
    }
}

/** Whole rupees with Indian digit grouping: ₹12,34,567. */
private fun BigDecimal.rupees(): String =
    "₹" + NumberFormat.getIntegerInstance(Locale("en", "IN")).format(setScale(0, RoundingMode.HALF_UP))
