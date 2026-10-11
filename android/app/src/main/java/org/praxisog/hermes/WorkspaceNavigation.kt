package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.praxisog.hermes.model.Session

internal enum class WorkspaceMode(val title: String) {
    CHATS("Chats"), PROJECTS("Projects"), TOOLS("Tools"), SETTINGS("Settings"), TODAY("Today")
}

// Shared with the single-line composer so both panes end on the same baseline.
internal val BottomControlHeight = 40.dp
internal val BottomBarPadding = 4.dp

@Composable
internal fun WorkspaceModes(selected: WorkspaceMode, onSelect: (WorkspaceMode) -> Unit) {
    HorizontalDivider()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(Modifier.fillMaxWidth().padding(vertical = BottomBarPadding)) {
            WorkspaceMode.entries.forEach { mode ->
                TextButton(onClick = { onSelect(mode) },
                    modifier = Modifier.weight(1f).heightIn(min = BottomControlHeight),
                    contentPadding = PaddingValues(horizontal = 1.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selected == mode) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = if (selected == mode) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(mode.title, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, softWrap = false,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
internal fun ToolsWorkspace(onSearch: () -> Unit, onContext: (() -> Unit)?,
    extraTools: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Open an app or turn a search into a new chat draft.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onSearch) { Text("Search apps & chats") }
        if (onContext != null) OutlinedButton(onClick = onContext) { Text("Session info") }
        if (extraTools != null) extraTools() else Text("Connect a server in Settings to use server tools.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Same expanded dialog proportions as prompt history. Existing app/chat search is shared. */
@Composable
internal fun WorkspaceSearch(
    sessions: List<Session>, apps: List<LaunchableApp>, now: Double, selectedId: String?,
    query: String, onQueryChange: (String) -> Unit, onOpen: (String) -> Unit,
    onLaunchApp: (LaunchableApp) -> Unit, onAsk: (String) -> Unit, onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(20.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.82f),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Conversations", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = query, onValueChange = onQueryChange, singleLine = true,
                    label = { Text("Search apps & chats") }, modifier = Modifier.fillMaxWidth())
                Text("Searches titles and previews in the loaded conversation list.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Box(Modifier.weight(1f)) {
                    SearchResults(query, sessions, apps, now, selectedId, onOpen, onLaunchApp, onAsk)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
