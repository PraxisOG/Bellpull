package org.praxisog.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversationToolbar(
    title: String,
    sideBySide: Boolean,
    showList: Boolean,
    onNavigate: () -> Unit,
    onNewSession: () -> Unit,
    creatingSession: Boolean,
    hasSession: Boolean,
    hasHistory: Boolean,
    showContext: Boolean,
    onContext: () -> Unit,
    onPrompts: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit = {},
    onShare: (() -> Unit)? = null,
    onArtifacts: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val navigate: @Composable () -> Unit = {
        IconButton(onClick = onNavigate, modifier = Modifier.semantics {
            contentDescription = if (!sideBySide) "Back to sessions"
                else if (showList) "Hide sessions" else "Show sessions"
        }) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 2.dp.toPx()
                if (sideBySide) {
                    listOf(.2f, .5f, .8f).forEach { y ->
                        drawLine(tint, Offset(0f, size.height * y), Offset(size.width, size.height * y), stroke, StrokeCap.Round)
                    }
                } else {
                    drawLine(tint, Offset(size.width, center.y), Offset(0f, center.y), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(0f, center.y), Offset(size.width * .4f, size.height * .1f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(0f, center.y), Offset(size.width * .4f, size.height * .9f), stroke, StrokeCap.Round)
                }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NewChatButton(onNewSession, creatingSession, highlighted = false)
                Box {
                    val actionTint = MaterialTheme.colorScheme.primary
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "More options" }) {
                        Canvas(Modifier.size(20.dp)) {
                            listOf(.15f, .5f, .85f).forEach { y -> drawCircle(actionTint, 1.8.dp.toPx(), Offset(center.x, size.height * y)) }
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (sideBySide && showContext && hasSession) "Hide session info" else "Session info") },
                            enabled = hasSession, onClick = { menuOpen = false; onContext() },
                        )
                        DropdownMenuItem(text = { Text("Prompt history") }, enabled = hasSession && hasHistory,
                            onClick = { menuOpen = false; onPrompts() })
                        DropdownMenuItem(text = { Text("Search in chat") }, enabled = hasSession && hasHistory,
                            onClick = { menuOpen = false; onSearch() })
                        DropdownMenuItem(text = { Text("View artifacts") }, enabled = hasSession,
                            onClick = { menuOpen = false; onArtifacts() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                        DropdownMenuItem(enabled = onShare != null, onClick = { menuOpen = false; onShare?.invoke() },
                            text = { Text("Share conversation") })
                    }
                }
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        if (LocalImmersiveHeaders.current) {
            CutoutHeader(leading = navigate, trailing = actions, title = {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            })
        } else Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            navigate()
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            actions()
        }
    }
}

@Composable
internal fun NewChatButton(onClick: () -> Unit, creating: Boolean, highlighted: Boolean = true) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (creating) .38f else 1f)
    IconButton(onClick = onClick, enabled = !creating,
        modifier = Modifier.testTag("toolbar-new-chat")
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape) else Modifier)
            .semantics { contentDescription = if (creating) "Starting new chat" else "New chat" }) {
        Canvas(Modifier.size(20.dp)) {
            drawLine(tint, Offset(center.x, 0f), Offset(center.x, size.height), 2.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(0f, center.y), Offset(size.width, center.y), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
