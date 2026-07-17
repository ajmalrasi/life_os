package com.lifeos.app

import app.cash.turbine.test
import com.lifeos.app.domain.model.ChatStreamEvent
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.Citation
import com.lifeos.app.domain.repository.ChatRepository
import com.lifeos.app.presentation.chat.ChatViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<ChatRepository>()

    @Before
    fun setUp() {
        // The ViewModel loads starter questions on init; keep it inert for these tests.
        coEvery { repository.suggestions(any()) } returns emptyList()
    }

    @Test
    fun `sending a message appends user and assistant turns with citations`() = runTest {
        every { repository.stream(any(), any(), any()) } returns flowOf(
            ChatStreamEvent.Delta("You sleep ~6h; "),
            ChatStreamEvent.Delta("execution drops on those days."),
            ChatStreamEvent.Done(
                citations = listOf(Citation("avg_sleep_score_30d", "62")),
                grounded = true,
            ),
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
        every { repository.stream(any(), capture(captured), any()) } returns flowOf(
            ChatStreamEvent.Delta("ok"),
            ChatStreamEvent.Done(emptyList(), true),
        )

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
