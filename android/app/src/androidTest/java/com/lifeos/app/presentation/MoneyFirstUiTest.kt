package com.lifeos.app.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.money.MoneySection
import com.lifeos.app.presentation.money.MoneySectionTabs
import com.lifeos.app.presentation.money.PeriodSelector
import com.lifeos.app.presentation.money.SpendPeriod
import com.lifeos.app.presentation.money.TransactionDetailDialog
import com.lifeos.app.presentation.money.TransactionSort
import com.lifeos.app.presentation.money.TransactionSortSheet
import com.lifeos.app.presentation.theme.LifeOsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MoneyFirstUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun money_sections_switch_without_leaving_money() {
        var selected by mutableStateOf(MoneySection.OVERVIEW)
        compose.setContent {
            LifeOsTheme {
                MoneySectionTabs(selected = selected, onSelect = { selected = it })
            }
        }

        compose.onNodeWithText("Overview").assertIsSelected()
        compose.onNodeWithText("Transactions").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(MoneySection.TRANSACTIONS, selected) }
    }

    @Test
    fun navigation_drawer_routes_to_secondary_lifeos_areas() {
        var selected: Destination? = null
        compose.setContent {
            LifeOsTheme {
                LifeOsNavigationDrawer(
                    currentRoute = Destination.MONEY.route,
                    onDestinationSelected = { selected = it },
                )
            }
        }

        compose.onNodeWithText("Ask").performClick()
        compose.runOnIdle { assertEquals(Destination.CHAT, selected) }
    }

    @Test
    fun period_label_opens_all_period_choices() {
        var selected: SpendPeriod? = null
        compose.setContent {
            LifeOsTheme {
                PeriodSelector(
                    label = "This month",
                    expanded = true,
                    canGoPrev = true,
                    canGoNext = false,
                    onToggleMenu = {},
                    onDismissMenu = {},
                    onSelectPeriod = { selected = it },
                    onPrev = {},
                    onNext = {},
                )
            }
        }

        compose.onNodeWithText("Year").performClick()
        compose.runOnIdle { assertEquals(SpendPeriod.YEAR, selected) }
    }

    @Test
    fun transaction_details_preserve_edit_and_reconciliation_actions() {
        var editClicked = false
        var refundClicked = false
        compose.setContent {
            LifeOsTheme {
                TransactionDetailDialog(
                    transaction = transaction(),
                    onDismiss = {},
                    onEditCategory = { editClicked = true },
                    onMarkAsRepaid = {},
                    onMarkAsRefunded = { refundClicked = true },
                )
            }
        }

        compose.onNodeWithText("Edit category").performClick()
        compose.onNodeWithText("Mark refunded").performClick()
        compose.runOnIdle {
            assertTrue(editClicked)
            assertTrue(refundClicked)
        }
    }

    @Test
    fun transaction_sort_sheet_selects_amount_order() {
        var selected = TransactionSort.RECENT
        compose.setContent {
            LifeOsTheme {
                TransactionSortSheet(
                    selected = selected,
                    onSelect = { selected = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Highest amount").performClick()
        compose.runOnIdle { assertEquals(TransactionSort.HIGHEST, selected) }
    }

    private fun transaction() = Transaction(
        id = 1,
        txnDate = LocalDate.of(2026, 8, 21),
        txnAt = null,
        amount = BigDecimal("1250.00"),
        currency = "INR",
        direction = "debit",
        counterparty = "Car Service Centre",
        category = null,
        spendCategory = "vehicle",
        account = "HDFC 6116",
        source = "upi",
        reference = null,
    )
}
