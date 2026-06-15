package com.lifeos.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyState() }
                item {
                    Text(
                        "TRY ASKING",
                        style = MaterialTheme.typography.labelMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                items(viewModel.suggestions) { s ->
                    SuggestionRow(text = s, onClick = { viewModel.send(s) })
                }
            }
            items(state.messages) { MessageBubble(it) }
            if (state.sending) {
                item { TypingIndicator() }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about your patterns…") },
                shape = RoundedCornerShape(18.dp),
                colors = fieldColors,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(LifeOsColors.Primary)
                    .clickable(enabled = !state.sending && state.input.isNotBlank()) { viewModel.send() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = LifeOsColors.OnPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(LifeOsColors.Primary, LifeOsColors.Secondary)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = LifeOsColors.OnPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            "What's shaping your performance?",
            style = MaterialTheme.typography.titleMedium,
            color = LifeOsColors.OnBackground,
        )
        Text(
            "I read your real sleep, recovery, mood, fitness, and execution data — and explain the why.",
            style = MaterialTheme.typography.bodyMedium,
            color = LifeOsColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LifeOsColors.Surface)
            .border(1.dp, LifeOsColors.SurfaceBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = LifeOsColors.OnBackground,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = LifeOsColors.Primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun TypingIndicator() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LifeOsColors.SurfaceMuted,
    ) {
        Text(
            "Thinking…",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = LifeOsColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 312.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 20.dp,
                    ),
                )
                .background(if (isUser) LifeOsColors.Primary else LifeOsColors.Surface)
                .then(
                    if (isUser) Modifier
                    else Modifier.border(
                        1.dp,
                        LifeOsColors.SurfaceBorder,
                        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp),
                    ),
                )
                .padding(14.dp),
        ) {
            Text(
                message.content,
                color = if (isUser) LifeOsColors.OnPrimary else LifeOsColors.OnBackground,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message.citations.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.citations.take(4).forEach { c ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isUser) Color.White.copy(alpha = 0.18f) else LifeOsColors.SurfaceMuted,
                        ) {
                            Text(
                                "${c.metric.replace('_', ' ')} · ${c.value}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isUser) LifeOsColors.OnPrimary.copy(alpha = 0.9f) else LifeOsColors.OnSurfaceMuted,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
