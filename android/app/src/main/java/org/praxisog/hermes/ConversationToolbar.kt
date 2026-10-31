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
    onOffline: (() -> Unit)? = null,
    onNewLocal: (() -> Unit)? = null,
    onActivity: (() -> Unit)? = null,
    onCanvas: (() -> Unit)? = null,
    thinkingMenu: (@Composable (() -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var thinkingOpen by remember { mutableStateOf(false) }
    val dismissMenus = { thinkingOpen = false; menuOpen = false }
    LaunchedEffect(hasSession, thinkingMenu != null) {
        if (!hasSession || thinkingMenu == null) thinkingOpen = false
    }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val navigate: @Composable () -> Unit = {
        NavigationAction("Chats", onNavigate, description = if (!sideBySide) "Back to sessions"
                else if (showList) "Hide sessions" else "Show sessions"
        ) {
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
                NewChatButton(onNewSession, creatingSession, highlighted = false, onNewLocal = onNewLocal)
                Box {
                    val actionTint = MaterialTheme.colorScheme.primary
                    NavigationAction("More", { thinkingOpen = false; menuOpen = true }, description = "More options") {
                        Canvas(Modifier.size(20.dp)) {
                            listOf(.15f, .5f, .85f).forEach { y -> drawCircle(actionTint, 1.8.dp.toPx(), Offset(center.x, size.height * y)) }
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = dismissMenus) {
                        if (thinkingOpen && thinkingMenu != null && hasSession) {
                            DropdownMenuItem(text = { Text("Thinking level") },
                                leadingIcon = { Text("‹") },
                                modifier = Modifier.semantics { contentDescription = "Back to more options" },
                                onClick = { thinkingOpen = false })
                            HorizontalDivider()
                            thinkingMenu(dismissMenus)
                        } else {
                            if (thinkingMenu != null) DropdownMenuItem(text = { Text("Thinking level") }, enabled = hasSession,
                                trailingIcon = { Text("›") }, onClick = { thinkingOpen = true })
                            DropdownMenuItem(
                                text = { Text(if (sideBySide && showContext && hasSession) "Hide session info" else "Session info") },
                                enabled = hasSession, onClick = { menuOpen = false; onContext() },
                            )
                            DropdownMenuItem(text = { Text("Prompt history") }, enabled = hasSession && hasHistory,
                                onClick = { menuOpen = false; onPrompts() })
                            if (onCanvas != null) DropdownMenuItem(text = { Text("Canvas") }, enabled = hasSession,
                                modifier = Modifier.testTag("open-shared-canvas"), onClick = { menuOpen = false; onCanvas() })
                            DropdownMenuItem(text = { Text("Search in chat") }, enabled = hasSession && hasHistory,
                                onClick = { menuOpen = false; onSearch() })
                            if (onOffline != null) DropdownMenuItem(text = { Text("Create local fork") }, enabled = hasSession,
                                onClick = { menuOpen = false; onOffline() })
                            DropdownMenuItem(text = { Text("View artifacts") }, enabled = hasSession,
                                onClick = { menuOpen = false; onArtifacts() })
                            if (onActivity != null) DropdownMenuItem(text = { Text("OpenCode activity") }, enabled = hasSession,
                                modifier = Modifier.testTag("open-coding-activity"),
                                onClick = { menuOpen = false; onActivity() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                            DropdownMenuItem(enabled = onShare != null, onClick = { menuOpen = false; onShare?.invoke() },
                                text = { Text("Share conversation") })
                        }
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
internal fun NewChatButton(onClick: () -> Unit, creating: Boolean, highlighted: Boolean = true, onNewLocal: (() -> Unit)? = null) {
    var localMenu by remember { mutableStateOf(false) }
    Box {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (creating) .38f else 1f)
    NavigationAction("New chat", onClick = onClick, enabled = !creating,
        modifier = Modifier.testTag("toolbar-new-chat")
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape) else Modifier)
            , description = if (creating) "Starting new chat" else "New chat",
            onLongClick = onNewLocal?.let { { localMenu = true } }) {
        Canvas(Modifier.size(20.dp)) {
            drawLine(tint, Offset(center.x, 0f), Offset(center.x, size.height), 2.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(0f, center.y), Offset(size.width, center.y), 2.dp.toPx(), StrokeCap.Round)
        }
    }
    DropdownMenu(expanded = localMenu, onDismissRequest = { localMenu = false }) {
        DropdownMenuItem(text = { Text("New local chat") }, onClick = { localMenu = false; onNewLocal?.invoke() })
    }
    }
}
