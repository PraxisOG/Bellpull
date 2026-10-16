package org.praxisog.hermes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.*

@Composable
internal fun CodingActivityPanel(snapshot: CodingSnapshot?, loading: Boolean, supported: Boolean,
    disconnected: Boolean, problem: String?, coordinator: String?, onRetry: () -> Unit,
    onInspect: (String) -> Unit, onChat: () -> Unit, onStop: () -> Unit,
    canStop: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.testTag("coding-activity")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Hermes · ${coordinator ?: "Conversation"}", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            TextButton(onClick = onRetry, enabled = supported) { Text("Refresh") }
        }
        if (!supported) Text("Coding activity needs a compatible Bellpull plugin and Hermes ownership hooks. Update the plugin, then refresh server capabilities in Settings.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        if (disconnected) Text("Disconnected · showing saved activity. Child state may have changed.",
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        if (snapshot?.gap == true) Text("Some output is missing or outside retained history. This view is incomplete.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).testTag("coding-gap"))
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        snapshot?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (snapshot?.works.isNullOrEmpty() && !loading) item {
                Text("No observed OpenCode work in this conversation.", style = MaterialTheme.typography.titleSmall)
                Text("The adapter observes Hermes-owned background OpenCode launches. JSON mode has structured parts; ordinary terminal mode has text only. Opening Activity never starts another task.", style = MaterialTheme.typography.bodySmall)
            }
            snapshot?.works.orEmpty().forEach { work ->
                item(key = "work:${work.id}") {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("OpenCode · ${work.state.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.titleSmall)
                            Text(work.openCodeSessionId ?: if (work.transport == "terminal_text") "Terminal text · no verified OpenCode session ID" else "Waiting for its session identity", style = MaterialTheme.typography.labelSmall)
                            Text("Hermes run ${work.runId}", style = MaterialTheme.typography.labelSmall)
                            work.workspaceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(work.note, style = MaterialTheme.typography.bodySmall)
                            if (work.workspaceId != null) TextButton(onClick = { onInspect(work.id) }) { Text("Workspace changes") }
                        }
                    }
                }
                items(snapshot?.rows.orEmpty().filter { it.workId == work.id }, key = { "${it.workId}:${it.key}" }) { row -> CodingActivityItem(row) }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onChat) { Text("Chat / follow up") }
            if (canStop) TextButton(onClick = onStop) { Text("Stop Hermes") }
        }
    }
}

@Composable
private fun CodingActivityItem(row: CodingRow) {
    var expanded by remember(row.workId, row.key) { mutableStateOf(row.kind == "text" || row.kind == "error") }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("coding-row:${row.key}")) {
        Column(Modifier.padding(10.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text((if (expanded) "▾ " else "▸ ") + row.label + (row.outcome?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) {
                row.command?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                SelectionContainer { Text(row.detail, style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (row.kind in setOf("tool", "output")) FontFamily.Monospace else FontFamily.Default) }
                if (row.truncated) Text("Output preview truncated", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun CodingWorkspaceDialog(workspace: CodingActivityWorkspace) {
    if (workspace.selectedWork == null) return
    AlertDialog(onDismissRequest = workspace::closeWorkspace, title = { Text("Workspace changes") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Read only · edits may belong to other work.", style = MaterialTheme.typography.bodySmall)
            if (workspace.workspaceLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            workspace.workspaceProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            workspace.workspace?.let { value ->
                Text(value.note, style = MaterialTheme.typography.bodySmall)
                if (value.truncated) Text("Preview limits reached; some changes are omitted.")
                val diff = value.diff
                if (diff != null) {
                    Text(value.file?.path.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(diff.take(65536), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState())) }
                    if (diff.length > 65536) Text("Showing the first 64 KiB of the diff.")
                    TextButton(onClick = { workspace.selectedWork?.let { workspace.inspect(it) } }) { Text("Back to files") }
                } else {
                    if (value.files.isEmpty()) Text("No changed files.")
                    value.files.forEach { file -> TextButton(onClick = { workspace.selectedWork?.let { workspace.inspect(it,file.id) } }) { Text(file.path) } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = workspace::closeWorkspace) { Text("Close") } })
}
