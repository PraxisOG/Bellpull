package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import org.praxisog.hermes.model.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun FilesToolbar(navigate: () -> Unit, settings: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        NavigationAction("Files", navigate, description = "Files list") {
            Canvas(Modifier.size(20.dp)) {
                listOf(.2f, .5f, .8f).forEach { y -> drawLine(tint, Offset(0f, size.height * y),
                    Offset(size.width, size.height * y), 2.dp.toPx(), StrokeCap.Round) }
            }
        }
        Text("Files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = settings) { Text("Settings") }
    }
}

@Composable
internal fun RecentLiveFiles(workspace: LiveFilesWorkspace?, artifacts: List<Artifact>,
    open: (String) -> Unit, preview: (Artifact) -> Unit, all: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = all) { Text("See all files") }
        }
        val documents = workspace?.entries.orEmpty().sortedByDescending { maxOf(it.accessedAt, it.referencedAt) }.take(3)
        documents.forEach { file -> TextButton(onClick = { open(file.id) }, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (file.pinned) Text("Pinned", style = MaterialTheme.typography.labelSmall)
        } }
        recentArtifacts(artifacts).take(3 - documents.size).forEach { file ->
            TextButton(onClick = { preview(file) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (documents.isEmpty() && artifacts.isEmpty()) Text("Ask Hermes to show a file here.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LiveFilesList(workspace: LiveFilesWorkspace?, modifier: Modifier = Modifier,
    open: (String) -> Unit, attachments: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { workspace?.refresh() }, enabled = workspace?.supported == true && !workspace.loading) { Text("Refresh") }
        }
        TextButton(onClick = attachments) { Text("Attachments & uploads") }
        if (workspace?.supported != true) Text("Connect a server with live file support to view referenced documents.", style = MaterialTheme.typography.bodySmall)
        else {
            OutlinedTextField(query, { query = it }, label = { Text("Find a file") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            workspace.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (workspace.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val entries = workspace.entries.filter { it.name.contains(query, true) || it.path.contains(query, true) }
            if (entries.isEmpty()) Text("No referenced files yet. Ask Hermes to show a file by its path.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true, false).forEach { pinned ->
                    val group = entries.filter { it.pinned == pinned }
                    if (group.isNotEmpty()) item { Text(if (pinned) "Pinned" else "Recent", style = MaterialTheme.typography.labelLarge) }
                    items(group, key = { it.id }) { file ->
                        OutlinedCard(onClick = { open(file.id) }, modifier = Modifier.fillMaxWidth().testTag("live-file-${file.id}")) {
                            Column(Modifier.padding(10.dp)) {
                                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(file.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { workspace.pin(file) }) { Text(if (file.pinned) "Unpin" else "Pin") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DocumentBlock(val markdown: String = "", val task: LiveFileTask? = null)
private fun documentBlocks(file: LiveFileSnapshot): List<DocumentBlock> {
    val tasks = file.tasks.associateBy { it.line }
    val blocks = mutableListOf<DocumentBlock>(); val text = StringBuilder()
    fun flush() { if (text.isNotBlank()) blocks.add(DocumentBlock(text.toString())); text.clear() }
    file.text.lineSequence().forEachIndexed { index, line ->
        val task = tasks[index]
        if (task == null) text.appendLine(line) else { flush(); blocks.add(DocumentBlock(task = task)) }
    }
    flush(); return blocks
}

@Composable
internal fun LiveFileViewer(workspace: LiveFilesWorkspace?) {
    val file = workspace?.snapshot
    var plain by rememberSaveable(file?.id) { mutableStateOf(workspace?.selected?.path?.endsWith(".txt", true) == true) }
    var itemText by rememberSaveable(file?.id) { mutableStateOf("") }
    var review by remember { mutableStateOf(false) }
    val canEdit = file?.access == "checklist" && workspace?.fresh == true && !workspace.busy && !workspace.loading && workspace.pending == null
    val blocks = remember(file) { file?.let(::documentBlocks).orEmpty() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text(workspace?.selected?.name ?: "Server files", style = MaterialTheme.typography.titleLarge)
            workspace?.selected?.path?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (file?.access == "checklist") "Checklist editing allowed" else "Read only", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { workspace?.refresh() }, enabled = workspace?.supported == true && !workspace.loading && !workspace.busy) { Text("Refresh") }
            }
            if (workspace?.loading == true || workspace?.busy == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            workspace?.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (file != null && !workspace.fresh) Text("Showing the last view. Refresh before editing.", style = MaterialTheme.typography.bodySmall)
        }
        if (workspace?.pending != null) item {
            Text("An edit is awaiting confirmation. Retry safely, or review the current file before making another edit.")
            workspace.pending?.body?.get("text")?.jsonPrimitive?.contentOrNull?.let { value -> SelectionContainer { Text(value) } }
            Row {
                TextButton(onClick = workspace::retryEdit, enabled = !workspace.busy) { Text("Retry edit") }
                TextButton(onClick = { review = true }, enabled = !workspace.busy) { Text("Review current file") }
            }
        }
        if (file == null) item { Text("Choose a file from the Files list. Hermes adds files here using the paths you give it.") }
        else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!plain, { plain = false }, label = { Text("Markdown") })
                    FilterChip(plain, { plain = true }, label = { Text("Plain text") })
                }
            }
            if (plain) item { SelectionContainer { Text(file.text, fontFamily = FontFamily.Monospace) } }
            else items(blocks) { block ->
                if (block.task == null) SelectionContainer { MarkdownAnswer(block.markdown) }
                else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .testTag("file-check-${block.task.line}")
                    .toggleable(block.task.checked, enabled = canEdit, role = Role.Checkbox,
                        onValueChange = { workspace.check(block.task.line, it) })) {
                    Checkbox(block.task.checked, null, enabled = canEdit)
                    MarkdownAnswer(block.task.text, Modifier.weight(1f))
                }
            }
            if (file.access == "checklist") item {
                OutlinedTextField(itemText, { itemText = it.take(1000) }, label = { Text("New list item") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { if (workspace.add(itemText)) itemText = "" }, enabled = canEdit && itemText.isNotBlank()) { Text("Add item") }
                Text("Saved as Markdown in the original server file.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (review) AlertDialog(onDismissRequest = { review = false }, title = { Text("Review current file?") },
        text = { Text("This stops retrying the pending edit. It does not undo a change already saved on the server. Refresh and check the list before editing again.") },
        confirmButton = { TextButton(onClick = { workspace?.pending?.fileId?.let { workspace.open(it) }; workspace?.reviewEdit(); review = false }) { Text("Review file") } },
        dismissButton = { TextButton(onClick = { review = false }) { Text("Keep retry") } })
}
