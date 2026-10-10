package org.praxisog.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message

/** Filled user messages and outline-only replies remain distinguishable without names. */
@Composable
internal fun MessageBubble(message: Message, dense: Boolean, onSelectText: (Message) -> Unit, modifier: Modifier = Modifier,
    reading: Boolean = false, onRead: (Message) -> Unit = {}, onBranch: (Message) -> Unit = {},
    canBranch: Boolean = false, onFollowUp: ((Message) -> Unit)? = null,
    canEdit: Boolean = false, onEdit: (Message) -> Unit = {},
    content: (@Composable () -> Unit)? = null) {
    val user = message.role == "user"
    // Freeze the selected text while streaming continues behind the menu.
    var selected by remember(message.id) { mutableStateOf<Message?>(null) }
    Box(modifier.testTag("bubble-${message.id}")) {
        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            shape = if (user) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            border = if (user) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {},
                onLongClickLabel = "Message actions", onLongClick = { selected = message })
                .semantics { contentDescription = if (user) "Your message" else "Hermes response" }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = if (dense) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (user && !dense) Text("You", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (content != null) content() else Text(
                    if (user) message.content.orEmpty() else transcriptText(message.content.orEmpty()),
                    style = MaterialTheme.typography.bodyLarge)
                if (user && !dense && onFollowUp != null) Row {
                    TextButton(onClick = { onFollowUp(message) }) { Text("Follow up") }
                    if (canEdit) TextButton(onClick = { onEdit(message) }) { Text("Fork & edit") }
                }
            }
        }
        DropdownMenu(expanded = selected != null, onDismissRequest = { selected = null },
            modifier = Modifier.testTag("message-action-popup")) {
            selected?.let { target ->
                if (user) UserMessageActions(target, onSelectText, onFollowUp, onEdit, canEdit, { selected = null })
                else MessageActions(target, reading, onRead, onBranch, canBranch, onFollowUp,
                    onSelectText, onAction = { selected = null })
            }
        }
    }
}
