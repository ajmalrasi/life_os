package com.lifeos.app.presentation

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
    fun settings_returns_to_today_and_back_stays_visible_after_scrolling() {
        openArea("Today")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Back to Today").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithText("Back to Today").assertIsDisplayed().performClick()
        compose.onNodeWithText("Your vitals", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Back to Money").performClick()
        compose.onNodeWithText("Overview").assertIsDisplayed()
    }

    @Test
    fun drawer_history_survives_recreation_and_system_back_retraces_it() {
        openArea("Today")
        openArea("Pulse")
        compose.onNodeWithText("Back to Today").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Back to Today").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Your vitals", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Back to Money").assertIsDisplayed()
        compose.onNodeWithText("Back to Money").performClick()
        compose.onNodeWithText("Overview").assertIsDisplayed()
    }

    @Test
    fun money_sections_remain_available_after_scrolling() {
        compose.onNodeWithText("Overview").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithText("Transactions").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Back to Overview").assertIsDisplayed().performClick()
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
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }
}
