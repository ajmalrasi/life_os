package com.lifeos.app.presentation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeos.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackNavigationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settings_sub_page_returns_to_settings_and_back_stays_visible_after_scrolling() {
        openArea("Settings")
        compose.onNodeWithText("Income, budgets, bill reminders").performClick()
        compose.onNodeWithText("Back to Settings").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithText("Back to Settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sync everything").assertExists()
        compose.onNodeWithText("Back to", substring = true).assertDoesNotExist()
    }

    @Test
    fun every_area_puts_the_menu_in_the_same_header() {
        listOf("Money", "Today", "Pulse", "Priority", "Insights", "Ask", "Trips", "Settings").forEach { area ->
            openArea(area)
            compose.onNodeWithContentDescription("Open navigation drawer").assertIsDisplayed()
            compose.onNodeWithText("Back to", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun drawer_areas_have_no_back_bar_and_system_back_returns_to_today() {
        openArea("Pulse")
        compose.onNodeWithText("Back to", substring = true).assertDoesNotExist()
        openArea("Money")
        compose.onNodeWithText("Back to", substring = true).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Overview").assertIsDisplayed()
        compose.onNodeWithText("Back to", substring = true).assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Overview").assertDoesNotExist()
        compose.onNodeWithText(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))).assertIsDisplayed()
    }

    @Test
    fun money_sections_remain_available_after_scrolling() {
        openArea("Money")
        compose.onNodeWithText("Overview").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithText("Transactions").assertIsDisplayed().performClick()
        compose.onNodeWithText("Transactions").assertIsSelected()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Overview").assertIsSelected()
        compose.onNodeWithText("Transactions").performClick()
        compose.onNodeWithText("Cards").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Open navigation drawer").performClick()
        compose.onNodeWithText("Choose an area").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Cards").assertIsSelected()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Overview").assertIsDisplayed().assertIsSelected()
    }

    private fun openArea(label: String) {
        compose.onNodeWithContentDescription("Open navigation drawer").performClick()
        // Drawer entries are tabs; Today's own cards also mention area names.
        compose.onNode(hasText(label) and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performClick()
        compose.waitForIdle()
    }
}
