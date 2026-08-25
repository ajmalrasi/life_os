package com.lifeos.app.domain.model

data class ConnectedMailAccount(
    val id: Int,
    val address: String,
    val displayLabel: String,
    val purposes: List<String>,
    val authorized: Boolean,
    val priorityLastError: String?,
)
