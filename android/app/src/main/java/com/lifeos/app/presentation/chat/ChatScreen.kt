package com.lifeos.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.DemoBanner
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.components.lifeOsFieldColors
import com.lifeos.app.presentation.theme.LifeOsColors

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fieldColors = lifeOsFieldColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader("Ask LifeOS", "Grounded answers — every claim cites your data.")
        if (state.showDemoWelcome) {
            Spacer(Modifier.height(10.dp))
            DemoBanner()
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { WelcomeCard(state.demoWelcome) }
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        viewModel.suggestions.forEach { s ->
                            SuggestionChip(text = s, onClick = { viewModel.send(s) })
                        }
                    }
                }
            }
            items(state.messages) { MessageBubble(it) }
            if (state.sending) {
                item { Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about your patterns…") },
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )
            IconButton(onClick = { viewModel.send() }, enabled = !state.sending) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = LifeOsColors.Primary)
            }
        }
    }
}

@Composable
private fun WelcomeCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = LifeOsColors.SurfaceMuted,
        border = androidx.compose.foundation.BorderStroke(1.dp, LifeOsColors.Primary.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("LifeOS", style = MaterialTheme.typography.labelMedium, color = LifeOsColors.Primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnBackground)
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(20.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = LifeOsColors.SecondarySoft,
            labelColor = LifeOsColors.Secondary,
        ),
    )
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (isUser) LifeOsColors.Primary else Color(0xFFF1F5F9))
                .padding(14.dp),
        ) {
            Text(
                message.content,
                color = if (isUser) LifeOsColors.OnPrimary else LifeOsColors.OnBackground,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message.citations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message.citations.joinToString { "${it.metric}=${it.value}" },
                    color = if (isUser) LifeOsColors.OnPrimary.copy(alpha = 0.85f) else LifeOsColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
