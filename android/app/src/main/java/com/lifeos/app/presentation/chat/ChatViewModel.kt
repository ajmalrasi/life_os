package com.lifeos.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.Citation
import com.lifeos.app.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val role: String,
    val content: String,
    val citations: List<Citation> = emptyList(),
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val suggestions: List<String> = DEFAULT_SUGGESTIONS,
)

// Shown immediately while the data-grounded set loads, and if that load fails.
private val DEFAULT_SUGGESTIONS = listOf(
    "How am I doing this week?",
    "Do I get more done on days I work out?",
    "Am I moving toward burnout?",
    "What should I focus on today?",
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // Tailor the starter questions to the user's actual recent data. Best-effort:
        // the repository falls back to defaults, so the empty state is never blank.
        viewModelScope.launch {
            val tailored = runCatching {
                chatRepository.suggestions(contextDays = 30)
            }.getOrDefault(DEFAULT_SUGGESTIONS)
            if (tailored.isNotEmpty()) {
                _state.value = _state.value.copy(suggestions = tailored)
            }
        }
    }

    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    fun send(text: String = _state.value.input) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.sending) return

        val history = _state.value.messages.map { ChatTurn(it.role, it.content) }
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage("user", message),
            input = "",
            sending = true,
        )

        viewModelScope.launch {
            val reply = runCatching {
                chatRepository.ask(message, history, contextDays = 30)
            }.getOrElse {
                return@getOrElse com.lifeos.app.domain.model.ChatAnswer(
                    answer = "Couldn't reach LifeOS backend. Check the server address in Settings.",
                    citations = emptyList(),
                    grounded = false,
                )
            }
            _state.value = _state.value.copy(
                messages = _state.value.messages + ChatMessage("assistant", reply.answer, reply.citations),
                sending = false,
            )
        }
    }
}
