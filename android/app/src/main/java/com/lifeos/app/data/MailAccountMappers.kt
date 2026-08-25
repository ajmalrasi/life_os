package com.lifeos.app.data

import com.lifeos.app.data.remote.dto.MailAccountDto
import com.lifeos.app.domain.model.ConnectedMailAccount

fun MailAccountDto.toDomain(): ConnectedMailAccount = ConnectedMailAccount(
    id = id,
    address = address,
    displayLabel = displayLabel,
    purposes = purposes,
    authorized = authorized,
    priorityLastError = priorityLastError,
)
