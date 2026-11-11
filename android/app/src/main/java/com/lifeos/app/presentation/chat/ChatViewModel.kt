package com.lifeos.app.presentation.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.ChatStreamEvent
import com.lifeos.app.domain.model.Citation
import com.lifeos.app.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class ChatMessage(
    val role: String,
    val content: String,
    val citations: List<Citation> = emptyList(),
    val incomplete: Boolean = false,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val status: String = "",
    val error: String? = null,
    val retryable: Boolean = true,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(input = savedState.get<String>(DRAFT_KEY).orEmpty()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // Show cached model questions immediately; check once for the background refresh.
        viewModelScope.launch {
            repeat(2) { attempt ->
                if (attempt == 1) delay(20_000)
                val tailored = chatRepository.suggestions(contextDays = 30)
                if (tailored.isNotEmpty()) {
                    _state.value = _state.value.copy(suggestions = tailored)
                }
            }
        }
    }

    fun onInputChange(text: String) {
        savedState[DRAFT_KEY] = text
        _state.value = _state.value.copy(input = text)
    }

    fun send(text: String? = null) {
        val message = (text ?: _state.value.input).trim()
        if (message.isEmpty() || _state.value.sending) return

        val history = _state.value.messages.filterNot { it.incomplete }.map { ChatTurn(it.role, it.content) }
        if (text == null) onInputChange("")
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage("user", message),
            sending = true,
            error = null,
            retryable = true,
            status = "Thinking",
        )

        viewModelScope.launch {
            runCatching {
                chatRepository.stream(message, history, contextDays = 30).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> appendAssistantText(event.text)
                        is ChatStreamEvent.Status -> _state.value = _state.value.copy(status = event.text)
                        is ChatStreamEvent.Error -> {
                            markIncomplete()
                            _state.value = _state.value.copy(error = event.text, retryable = event.retryable)
                        }
                        is ChatStreamEvent.Done -> {
                            finishAssistant(event.citations)
                            if (event.incomplete) {
                                markIncomplete()
                                if (_state.value.error == null) {
                                    _state.value = _state.value.copy(error = "Response interrupted. Please try again.")
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                markIncomplete()
                _state.value = _state.value.copy(error = "Connection interrupted. Check Settings and try again.", retryable = true)
            }
            _state.value = _state.value.copy(sending = false, status = "")
        }
    }

    fun retry() {
        if (_state.value.sending) return
        val index = _state.value.messages.indexOfLast { it.role == "user" }
        if (index < 0) return
        val question = _state.value.messages[index].content
        _state.value = _state.value.copy(messages = _state.value.messages.take(index))
        send(question)
    }

    private fun markIncomplete() {
        val messages = _state.value.messages.toMutableList()
        if (messages.lastOrNull()?.role == "assistant") {
            messages[messages.lastIndex] = messages.last().copy(incomplete = true)
            _state.value = _state.value.copy(messages = messages)
        }
    }

    private fun appendAssistantText(text: String) {
        val messages = _state.value.messages.toMutableList()
        val last = messages.lastOrNull()
        if (last?.role == "assistant") {
            messages[messages.lastIndex] = last.copy(content = last.content + text)
        } else {
            messages += ChatMessage("assistant", text)
        }
        _state.value = _state.value.copy(messages = messages)
    }

    private companion object {
        const val DRAFT_KEY = "askDraft"
    }

    private fun finishAssistant(citations: List<Citation>) {
        val messages = _state.value.messages.toMutableList()
        val last = messages.lastOrNull()
        if (last?.role == "assistant") {
            messages[messages.lastIndex] = last.copy(citations = citations)
            _state.value = _state.value.copy(messages = messages)
        }
    }
}
