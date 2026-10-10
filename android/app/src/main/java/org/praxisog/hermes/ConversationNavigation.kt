package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.praxisog.hermes.model.Message

internal enum class ChatNavigation { PROMPTS, SEARCH }

/** Local transcript navigation; no network request is needed to revisit a prompt. */
@Composable
internal fun ConversationNavigator(
    mode: ChatNavigation,
    messages: List<Message>,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(mode) { mutableStateOf("") }
    val searching = mode == ChatNavigation.SEARCH
    val matches = remember(mode, query, messages) {
        if (!searching) messages.filter { it.role == "user" }
        else if (query.isBlank()) emptyList()
        else messages.filter { it.content?.contains(query.trim(), ignoreCase = true) == true }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(20.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.82f),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (searching) "Search in chat" else "Prompt history", style = MaterialTheme.typography.titleLarge)
                if (searching) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Find in this conversation") },
                        modifier = Modifier.fillMaxWidth().testTag("chat-search"), shape = MaterialTheme.shapes.medium)
                }
                Text(when {
                    searching && query.isBlank() -> "Search messages and tool output."
                    searching -> "${matches.size} ${if (matches.size == 1) "match" else "matches"} · tap to jump"
                    else -> "${matches.size} ${if (matches.size == 1) "prompt" else "prompts"} · tap to jump"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (matches.isEmpty()) item {
                        Text(if (searching) { if (query.isBlank()) "Start typing to find a message." else "No matching messages." }
                            else "Your prompts will appear here once you send a message.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    itemsIndexed(matches, key = { _, message -> message.id }) { index, message ->
                        Surface(onClick = { onJump(message.id) }, shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().testTag("jump-to-${message.id}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (!searching) "Prompt ${index + 1}" else when (message.role) {
                                    "user" -> "You"
                                    "assistant" -> "Hermes"
                                    "tool" -> toolDisplayName(message.toolName)
                                    else -> message.role
                                }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                val content = message.content.orEmpty()
                                val matchIndex = if (searching) content.indexOf(query.trim(), ignoreCase = true).coerceAtLeast(0) else 0
                                val start = (matchIndex - 50).coerceAtLeast(0)
                                Text((if (start > 0) "…" else "") + content.drop(start).take(300),
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
