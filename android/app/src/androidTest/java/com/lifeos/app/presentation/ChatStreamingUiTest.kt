package com.lifeos.app.presentation

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeos.app.presentation.chat.ChatMessage
import com.lifeos.app.presentation.chat.StreamingMessageList
import com.lifeos.app.presentation.theme.LifeOsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatStreamingUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun growing_reply_follows_bottom_and_user_can_pause_then_resume() {
        var assistantText by mutableStateOf(longReply("Initial end"))
        compose.setContent {
            LifeOsTheme {
                StreamingMessageList(
                    messages = listOf(
                        ChatMessage("user", "Write a long essay"),
                        ChatMessage("assistant", assistantText),
                    ),
                    sending = true,
                    status = "Writing",
                    modifier = Modifier.height(360.dp),
                )
            }
        }

        compose.onNodeWithText("Initial end").assertIsDisplayed()
        compose.runOnIdle { assistantText += "\nStreamed latest one" }
        compose.onNodeWithText("Streamed latest one").assertIsDisplayed()

        compose.onRoot().performTouchInput { swipeDown() }
        compose.onNodeWithText("Jump to latest").assertIsDisplayed()
        compose.runOnIdle { assistantText += "\nStreamed latest two" }
        compose.onNodeWithText("Streamed latest two").assertIsNotDisplayed()

        compose.onNodeWithText("Jump to latest").performClick()
        compose.onNodeWithText("Streamed latest two").assertIsDisplayed()
    }

    private fun longReply(lastLine: String): String = buildString {
        repeat(45) { line -> append("Paragraph line $line with enough words to occupy space.\n") }
        append(lastLine)
    }
}
