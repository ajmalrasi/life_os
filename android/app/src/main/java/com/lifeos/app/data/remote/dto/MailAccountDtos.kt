package com.lifeos.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MailAccountDto(
    val id: Int,
    val address: String,
    @SerialName("display_label") val displayLabel: String,
    val purposes: List<String> = emptyList(),
    val authorized: Boolean = false,
    @SerialName("priority_last_error") val priorityLastError: String? = null,
)

@Serializable
data class MailConnectionStartDto(
    val purpose: String,
    val email: String? = null,
    @SerialName("display_label") val displayLabel: String,
    @SerialName("callback_port") val callbackPort: Int,
    @SerialName("force_reauthorize") val forceReauthorize: Boolean,
)

@Serializable
data class MailConnectionStartResultDto(
    @SerialName("requires_authorization") val requiresAuthorization: Boolean,
    val account: MailAccountDto? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_secret") val sessionSecret: String? = null,
    @SerialName("authorization_url") val authorizationUrl: String? = null,
)

@Serializable
data class MailConnectionCompleteDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_secret") val sessionSecret: String,
    @SerialName("callback_url") val callbackUrl: String,
)
