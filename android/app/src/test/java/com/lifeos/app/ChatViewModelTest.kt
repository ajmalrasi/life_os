package com.lifeos.app

import app.cash.turbine.test
import com.lifeos.app.domain.model.ChatStreamEvent
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.Citation
import com.lifeos.app.domain.repository.ChatRepository
import com.lifeos.app.presentation.chat.ChatViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun `partial failures stay visible and retry replaces the failed attempt`() = runTest {
        every { repository.stream(any(), any(), any()) } returns flowOf(
            ChatStreamEvent.Delta("Partial"),
            ChatStreamEvent.Error("Timed out"),
            ChatStreamEvent.Done(emptyList(), false, incomplete = true),
        )
        val vm = ChatViewModel(repository)
        vm.send("My spending")
        advanceUntilIdle()
        assertEquals("Partial", vm.state.value.messages.last().content)
        assertTrue(vm.state.value.messages.last().incomplete)
        assertEquals("Timed out", vm.state.value.error)

        every { repository.stream(any(), any(), any()) } returns flowOf(
            ChatStreamEvent.Delta("Complete"),
            ChatStreamEvent.Done(emptyList(), false),
        )
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.messages.size)
        assertEquals("Complete", vm.state.value.messages.last().content)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `transport failure marks a partial answer incomplete`() = runTest {
        every { repository.stream(any(), any(), any()) } returns flow {
            emit(ChatStreamEvent.Delta("Partial"))
            throw java.io.IOException("disconnected")
        }
        val vm = ChatViewModel(repository)
        vm.send("Hello")
        advanceUntilIdle()
        assertTrue(vm.state.value.messages.last().incomplete)
        assertTrue(vm.state.value.error != null)
        assertTrue(!vm.state.value.sending)
    }

    @Test
    fun `no canned suggestions and one delayed refresh`() = runTest {
        coEvery { repository.suggestions(any()) } returnsMany listOf(
            emptyList(), listOf("Model question?"),
        )
        val vm = ChatViewModel(repository)
        assertTrue(vm.state.value.suggestions.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("Model question?"), vm.state.value.suggestions)
        coVerify(exactly = 2) { repository.suggestions(30) }
    }

    @Test
    fun `completed conversation is forwarded with the next question`() = runTest {
        val captured = mutableListOf<List<ChatTurn>>()
        every { repository.stream(any(), capture(captured), any()) } returns flowOf(
            ChatStreamEvent.Delta("ok"),
            ChatStreamEvent.Done(emptyList(), true),
        )

        val vm = ChatViewModel(repository)
        vm.send("first")
        advanceUntilIdle()
        vm.send("second")
        advanceUntilIdle()

        assertTrue(captured[0].isEmpty())
        assertEquals(
            listOf(ChatTurn("user", "first"), ChatTurn("assistant", "ok")),
            captured[1],
        )
    }
}
