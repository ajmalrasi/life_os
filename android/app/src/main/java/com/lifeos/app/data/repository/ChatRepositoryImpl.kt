package com.lifeos.app.data.repository

import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.remote.dto.ChatRequestDto
import com.lifeos.app.data.remote.dto.ChatStreamEventDto
import com.lifeos.app.data.toDto
import com.lifeos.app.domain.model.ChatAnswer
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.ChatStreamEvent
import com.lifeos.app.domain.model.Citation
import com.lifeos.app.domain.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val api: LifeOsApi,
    private val json: Json,
) : ChatRepository {

    override suspend fun ask(
        message: String,
        history: List<ChatTurn>,
        contextDays: Int,
    ): ChatAnswer {
        val response = api.chat(
            ChatRequestDto(
                message = message,
                history = history.map { it.toDto() },
                contextDays = contextDays,
            )
        )
        response.error?.let { throw java.io.IOException(it) }
        return ChatAnswer(
            answer = response.answer,
            citations = response.citations.map { Citation(it.metric, it.value, it.label) },
            grounded = response.grounded,
        )
    }

    override fun stream(
        message: String,
        history: List<ChatTurn>,
        contextDays: Int,
    ): Flow<ChatStreamEvent> = flow {
        val body = api.streamChat(
            ChatRequestDto(
                message = message,
                history = history.map { it.toDto() },
                contextDays = contextDays,
            )
        )
        body.use {
            val source = it.source()
            var completed = false
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = json.decodeFromString<ChatStreamEventDto>(line)
                when (event.type) {
                    "status" -> event.text?.let { emit(ChatStreamEvent.Status(it)) }
                    "error" -> emit(ChatStreamEvent.Error(event.text ?: "Response interrupted.", event.retryable))
                    "delta" -> event.text?.takeIf(String::isNotEmpty)?.let { text ->
                        emit(ChatStreamEvent.Delta(text))
                    }
                    "done" -> {
                        completed = true
                        emit(
                            ChatStreamEvent.Done(
                                citations = event.citations.map { citation ->
                                    Citation(citation.metric, citation.value, citation.label)
                                },
                                grounded = event.grounded,
                                incomplete = event.incomplete,
                            ),
                        )
                    }
                }
            }
            if (!completed) throw java.io.IOException("Response interrupted. Please try again.")
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun suggestions(contextDays: Int): List<String> =
        runCatching { api.chatSuggestions(contextDays).questions }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
}
