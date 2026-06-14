package com.lifeos.app.data.repository

import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.remote.dto.ChatRequestDto
import com.lifeos.app.data.toDto
import com.lifeos.app.domain.model.ChatAnswer
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.Citation
import com.lifeos.app.domain.repository.ChatRepository
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val api: LifeOsApi,
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
        return ChatAnswer(
            answer = response.answer,
            citations = response.citations.map { Citation(it.metric, it.value) },
            grounded = response.grounded,
        )
    }
}
