package com.lifeos.app

import app.cash.turbine.test
import com.lifeos.app.domain.model.ChatAnswer
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.Citation
import com.lifeos.app.domain.repository.ChatRepository
import com.lifeos.app.presentation.chat.ChatViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<ChatRepository>()

    @Test
    fun `sending a message appends user and assistant turns with citations`() = runTest {
        coEvery { repository.ask(any(), any(), any()) } returns ChatAnswer(
            answer = "You sleep ~6h; execution drops on those days.",
            citations = listOf(Citation("avg_sleep_score_30d", "62")),
            grounded = true,
        )

        val vm = ChatViewModel(repository)
        vm.send("Why am I unproductive?")

        vm.state.test {
            // Drain until the assistant reply lands.
            var latest = awaitItem()
            while (latest.sending || latest.messages.size < 2) {
                latest = awaitItem()
            }
            assertEquals(2, latest.messages.size)
            assertEquals("user", latest.messages[0].role)
            assertEquals("assistant", latest.messages[1].role)
            assertTrue(latest.messages[1].citations.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blank message is ignored`() = runTest {
        val vm = ChatViewModel(repository)
        vm.send("   ")
        assertEquals(0, vm.state.value.messages.size)
    }

    @Test
    fun `history is forwarded to repository`() = runTest {
        val captured = mutableListOf<List<ChatTurn>>()
        coEvery { repository.ask(any(), capture(captured), any()) } returns
            ChatAnswer("ok", emptyList(), true)

        val vm = ChatViewModel(repository)
        vm.send("first")
        vm.state.test {
            var latest = awaitItem()
            while (latest.messages.size < 2) latest = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // First call has empty history.
        assertTrue(captured.first().isEmpty())
    }
}
