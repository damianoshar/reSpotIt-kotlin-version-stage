package com.spotifydashboard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spotifydashboard.app.api.LLMChatSummaryDto
import com.spotifydashboard.app.ui.theme.SpotifyCardGray
import com.spotifydashboard.app.ui.theme.SpotifyChipGray
import com.spotifydashboard.app.ui.theme.SpotifyGreen
import com.spotifydashboard.app.ui.theme.SpotifyLightGray
import com.spotifydashboard.app.utils.aiChatTimeLabel
import com.spotifydashboard.app.utils.aiModelLabel

/**
 * AI assistant: talks to the local model through the media server
 * (`/api/v1/llm` routes). Open WebUI owns the chats; the server keeps the
 * current-chat pointer, so reopening the dialog resumes where the user left
 * off. When history is unavailable the dialog degrades to stateless chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantDialog(
    state: AiAssistantState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onToggleHistory: () -> Unit,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDeleteChat: (LLMChatSummaryDto) -> Unit,
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<LLMChatSummaryDto?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.busy) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || !state.canSend) return
        onSend(text)
        input = ""
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("AI assistant")
                            Text(
                                "Local model through your reSpotIt server",
                                style = MaterialTheme.typography.labelSmall,
                                color = SpotifyLightGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        if (state.historyAvailable) {
                            TextButton(onClick = onToggleHistory) {
                                Text(if (state.historyOpen) "Hide chats" else "Chats (${state.chats.size})")
                            }
                            TextButton(onClick = onNewChat, enabled = !state.busy) {
                                Text("+ New")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )

                // --- Status -------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BoxDot(ok = state.available)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            state.statusLoading -> "Checking local model…"
                            state.available -> buildString {
                                append("Local model: ")
                                append(aiModelLabel(state.status?.model))
                                if (state.historyAvailable) append(" · chats saved")
                            }
                            else -> "Local model unavailable"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        modifier = Modifier.weight(1f)
                    )
                    if (!state.statusLoading && !state.available) {
                        IconButton(onClick = onRetry) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = SpotifyLightGray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (state.statusError != null) {
                    Banner(state.statusError, error = true)
                } else if (state.status != null && !state.available) {
                    Banner(
                        state.status.error ?: "The local model is not available.",
                        error = true
                    )
                }

                if (state.available && !state.historyAvailable) {
                    Banner("Chat history is off on this server, so answers are not saved.")
                }

                if (state.chatTitle.isNotBlank()) {
                    Text(
                        "Current chat: ${state.chatTitle}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SpotifyLightGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                // --- Recent chats ------------------------------------------
                if (state.historyAvailable && state.historyOpen) {
                    Surface(
                        color = SpotifyCardGray,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "RECENT CHATS",
                                style = MaterialTheme.typography.labelSmall,
                                color = SpotifyLightGray,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                            when {
                                state.historyLoading -> Text(
                                    "Loading…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                    modifier = Modifier.padding(8.dp)
                                )
                                state.chats.isEmpty() -> Text(
                                    "No saved chats yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                    modifier = Modifier.padding(8.dp)
                                )
                                else -> LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                    items(state.chats, key = { it.id }) { row ->
                                        ChatRow(
                                            row = row,
                                            active = row.id == state.chatId,
                                            enabled = !state.busy,
                                            onOpen = { onOpenChat(row.id) },
                                            onDelete = { pendingDelete = row }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Messages ----------------------------------------------
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.messages.isEmpty()) {
                        item {
                            EmptyAssistant(
                                available = state.available,
                                historyAvailable = state.historyAvailable,
                                chatsCount = state.chats.size,
                                enabled = state.canSend,
                                onPrompt = onSend
                            )
                        }
                    }
                    itemsIndexed(state.messages) { _, message ->
                        MessageBubble(message)
                    }
                    if (state.busy) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = SpotifyGreen
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Thinking…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray
                                )
                            }
                        }
                    }
                }

                if (state.error != null) {
                    Banner(state.error, error = true)
                }
                if (state.notice != null) {
                    Banner(state.notice, error = false)
                }

                // --- Compose ------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    !event.isShiftPressed
                                ) {
                                    submit()
                                    true
                                } else {
                                    false
                                }
                            },
                        placeholder = {
                            Text(
                                if (state.available) "Ask about your library…"
                                else "Waiting for the local model…"
                            )
                        },
                        maxLines = 4,
                        enabled = state.available && !state.busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { submit() },
                        enabled = state.canSend && input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send")
                    }
                }

                Text(
                    "Enter sends · Shift+Enter adds a line · ${state.messages.size} message" +
                        if (state.messages.size == 1) "" else "s",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyLightGray,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                )
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${(row.title ?: "").trim().ifBlank { "Untitled chat" }}\" will be deleted. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDeleteChat(row)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BoxDot(ok: Boolean) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                color = if (ok) SpotifyGreen else Color(0xFF888888),
                shape = RoundedCornerShape(4.dp)
            )
    )
}

@Composable
private fun Banner(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) Color(0xFFFF8A80) else Color(0xFFFFD666),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun ChatRow(
    row: LLMChatSummaryDto,
    active: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (active) Color(0x141DB954) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled, onClick = onOpen)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.title?.trim().orEmpty().ifBlank { "Untitled chat" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val stamp = aiChatTimeLabel(row.updatedAt)
            if (stamp.isNotBlank()) {
                Text(
                    stamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyLightGray
                )
            }
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete ${row.title ?: "chat"}",
                tint = SpotifyLightGray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EmptyAssistant(
    available: Boolean,
    historyAvailable: Boolean,
    chatsCount: Int,
    enabled: Boolean,
    onPrompt: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Icon(
            Icons.Default.SmartToy,
            contentDescription = null,
            tint = SpotifyGreen,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when {
                !available -> "The AI assistant needs the media server and a reachable local model."
                historyAvailable && chatsCount > 0 ->
                    "Start with a quick question, or open Chats to resume an earlier conversation."
                else -> "Start with a quick question, or type your own below."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyLightGray
        )
        Spacer(modifier = Modifier.height(12.dp))
        AI_QUICK_PROMPTS.forEach { (label, prompt) ->
            OutlinedButton(
                onClick = { onPrompt(prompt) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AiChatMessage) {
    val user = message.role == "user"
    var showReasoning by remember(message.content) { mutableStateOf(false) }
    var showTools by remember(message.content) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start
    ) {
        Text(
            if (user) "You" else "Assistant",
            style = MaterialTheme.typography.labelSmall,
            color = SpotifyLightGray
        )
        Surface(
            color = if (user) SpotifyChipGray else SpotifyCardGray,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (message.content.isBlank()) {
                    Text(
                        "The model used its whole budget on reasoning and returned no answer. " +
                            "Try a shorter question, or ask it to answer directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray
                    )
                } else {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                message.reasoning?.let { reasoning ->
                    TextButton(onClick = { showReasoning = !showReasoning }) {
                        Text(
                            if (showReasoning) "Hide reasoning" else "Show reasoning",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (showReasoning) {
                        Text(
                            reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray
                        )
                    }
                }
                if (message.toolCalls.isNotEmpty()) {
                    TextButton(onClick = { showTools = !showTools }) {
                        Text(
                            if (showTools) "Hide Spotify tools" else
                                "Spotify tools used (${message.toolCalls.size})",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (showTools) {
                        message.toolCalls.forEach { call ->
                            Text(
                                call.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = SpotifyGreen
                            )
                            val args = call.arguments?.toString().orEmpty()
                            if (args.isNotBlank()) {
                                Text(
                                    args,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SpotifyLightGray
                                )
                            }
                            if (call.result.isNotBlank()) {
                                Text(
                                    call.result,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SpotifyLightGray
                                )
                            }
                            Divider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
