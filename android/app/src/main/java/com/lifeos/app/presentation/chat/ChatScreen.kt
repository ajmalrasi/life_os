package com.lifeos.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.AreaHeader
import com.lifeos.app.presentation.theme.LifeOsColors

@Composable
fun ChatScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding(),
    ) {
        AreaHeader("Ask")
        StreamingMessageList(
            messages = state.messages,
            sending = state.sending,
            status = state.status,
            suggestions = state.suggestions,
            onSuggestion = { suggestion ->
                if (state.input.isBlank()) {
                    viewModel.send(suggestion)
                    dismissKeyboard()
                }
            },
            suggestionsEnabled = state.input.isBlank(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        ChatComposer(
            state = state,
            onInputChange = viewModel::onInputChange,
            onRetry = viewModel::retry,
            onSend = {
                viewModel.send()
                dismissKeyboard()
            },
        )
    }
}

@Composable
private fun ChatComposer(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.error?.let { error ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (state.retryable && !state.sending) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
        }
        val canSend = !state.sending && state.input.isNotBlank()
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LifeOsColors.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, LifeOsColors.SurfaceBorder),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(end = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Ask") },
                    minLines = 1,
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = LifeOsColors.OnBackground,
                        unfocusedTextColor = LifeOsColors.OnBackground,
                        focusedPlaceholderColor = LifeOsColors.OnSurfaceMuted,
                        unfocusedPlaceholderColor = LifeOsColors.OnSurfaceMuted,
                        cursorColor = LifeOsColors.Primary,
                    ),
                )
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                ) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(if (canSend) LifeOsColors.Primary else LifeOsColors.SurfaceMuted),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            tint = if (canSend) LifeOsColors.OnPrimary else LifeOsColors.OnSurfaceMuted,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Follows the growing bottom edge of a streamed assistant reply. A LazyColumn
 * item can become taller than the viewport; scrolling merely to that item's
 * index leaves its newest text below the screen, so each update scrolls to the
 * list's true maximum instead. A user drag pauses following until they return
 * to the bottom or tap the affordance.
 */
@Composable
internal fun StreamingMessageList(
    messages: List<ChatMessage>,
    sending: Boolean,
    status: String,
    suggestions: List<String> = emptyList(),
    onSuggestion: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    suggestionsEnabled: Boolean = true,
) {
    val listState = rememberLazyListState()
    val waitingForFirstToken = sending && messages.lastOrNull()?.role == "user"
    val showStatus = sending && (waitingForFirstToken || status.isNotBlank())
    val itemCount = messages.size + if (showStatus) 1 else 0
    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) followLatest = false
                return Offset.Zero
            }
        }
    }

    fun isAtBottom(): Boolean {
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return true
        return last.index == layout.totalItemsCount - 1 &&
            last.offset + last.size <= layout.viewportEndOffset + 8
    }

    LaunchedEffect(sending) {
        if (sending) followLatest = true
    }
    LaunchedEffect(itemCount, lastContentLength, followLatest, viewportHeight) {
        if (followLatest && itemCount > 0) {
            // Wait until the streamed text has been measured, then request an
            // offset beyond its end. LazyColumn clamps it to the true maximum,
            // including within one very tall Markdown message.
            withFrameNanos { }
            listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && !followLatest && isAtBottom()) {
            followLatest = true
        }
    }

    Box(modifier = modifier.onSizeChanged { viewportHeight = it.height }) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScrollConnection),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = if (messages.isEmpty()) {
                Arrangement.spacedBy(12.dp)
            } else {
                Arrangement.spacedBy(12.dp, Alignment.Bottom)
            },
        ) {
            if (messages.isEmpty()) {
                item { EmptyState() }
                if (suggestionsEnabled && suggestions.isNotEmpty()) item {
                    Text(
                        "TRY ASKING",
                        style = MaterialTheme.typography.labelMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                items(if (suggestionsEnabled) suggestions else emptyList()) { suggestion ->
                    SuggestionRow(text = suggestion, onClick = { onSuggestion(suggestion) })
                }
            }
            items(messages) { MessageBubble(it) }
            if (waitingForFirstToken) {
                item { TypingIndicator(status.ifBlank { "Thinking" }) }
            } else if (showStatus) {
                item { Text(status, style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted) }
            }
        }

        if (messages.isNotEmpty() && !followLatest) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .clickable { followLatest = true },
                shape = RoundedCornerShape(50),
                color = LifeOsColors.Primary,
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                        tint = LifeOsColors.OnPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Jump to latest", style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnPrimary)
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = LifeOsColors.PrimarySoft) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LifeOsColors.Primary)
            }
        }
        Text(
            "What’s on your mind?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = LifeOsColors.OnBackground,
        )
        Text(
            "Ask about your spending, health, or anything else.",
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
private fun TypingIndicator(status: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LifeOsColors.SurfaceMuted,
    ) {
        Text(
            status,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = LifeOsColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    if (isUser) {
        Box(Modifier.fillMaxWidth().padding(start = 40.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
                color = LifeOsColors.Primary,
                contentColor = LifeOsColors.OnPrimary,
            ) {
                Text(message.content, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = LifeOsColors.Primary)
                Text("LifeOS", style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnBackground)
            }
            Spacer(Modifier.height(7.dp))
            MarkdownText(text = message.content, color = LifeOsColors.OnBackground)
            if (message.citations.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Retrieved sources", style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.citations.take(4).forEach { c ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = LifeOsColors.SurfaceMuted,
                        ) {
                            Text(
                                "${c.label ?: c.metric.replace('_', ' ')} · ${c.value}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = LifeOsColors.OnSurfaceMuted,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            if (message.incomplete) {
                Text("Incomplete response", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
