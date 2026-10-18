package org.praxisog.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class OfflineChatActivity : ComponentActivity() {
    private var workspace: OfflineWorkspace? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val runtime = BellpullRuntime.get(this); runtime.restore()
        workspace = runtime.offlineWorkspace
        val active = workspace ?: run { finish(); return }
        if (savedInstanceState == null) active.back()
        val preferences = BellpullSettings(this).value
        setContent {
            val state by active.state.collectAsState()
            HermesTheme(preferences.appearance, preferences.palette, preferences.customAccent) {
                OfflineScreen(state, intent.getStringExtra("sourceId"), intent.getStringExtra("sourceTitle") ?: "Conversation", active,
                    onSetup = { startActivity(Intent(this, LocalLlmActivity::class.java)) },
                    onRemote = { id -> runtime.store?.loadSessions(); runtime.store?.openSession(id); finish() }, onClose = ::finish)
            }
        }
    }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) workspace?.background() }
}

@Composable
internal fun OfflineScreen(state: OfflineWorkspaceState, sourceId: String?, sourceTitle: String, workspace: OfflineWorkspace,
    onSetup: () -> Unit, onRemote: (String) -> Unit, onClose: () -> Unit, embedded: Boolean = false) {
    val fork = state.current
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, state.busy) {
        val previous = view.keepScreenOn
        if (state.busy) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    var setup by rememberSaveable(fork?.id) { mutableStateOf(false) }
    var query by rememberSaveable(fork?.id) { mutableStateOf("") }
    var brief by rememberSaveable(fork?.id) { mutableStateOf(fork?.brief.orEmpty()) }
    var review by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    var follow by remember(fork?.id) { mutableStateOf(true) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging, list.canScrollForward) {
        if (dragging) follow = !list.canScrollForward
        else if (!list.canScrollForward) follow = true
    }
    LaunchedEffect(fork?.turns?.lastOrNull()?.text, fork?.turns?.lastOrNull()?.thought) {
        val count = state.searchResults.size + (fork?.turns?.size ?: 0)
        if (follow && count > 0) list.scrollToItem(count - 1, Int.MAX_VALUE)
    }
    LaunchedEffect(state.searchResults) { if (state.searchResults.isNotEmpty()) list.scrollToItem(0) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.safeDrawingPadding()).imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!embedded) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (fork == null) "Offline chats" else fork.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    Text("On this phone • Gemma 4 E2B • CPU", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { if (fork == null) onClose() else workspace.back() }, enabled = !state.busy) { Text(if (fork == null) "Close" else "Chats") }
            }
            if (embedded) Text("Local · Gemma 4 E2B · saved on this phone", style = MaterialTheme.typography.labelMedium)
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (fork == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { workspace.create(null) }, enabled = !state.busy) { Text("New local chat") }
                    OutlinedButton(onClick = onSetup, enabled = !state.busy) { Text("Model setup") }
                }
                if (sourceId != null) {
                    Text("Source: $sourceTitle")
                    Text("Fork a saved copy, or download the full chat and build its search index while online.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { workspace.create(sourceId, sourceTitle) }, enabled = !state.busy) { Text("Fork saved chat") }
                        OutlinedButton(onClick = { workspace.prepare(sourceId, sourceTitle) }, enabled = !state.busy) { Text("Prepare for offline") }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.forks, key = { it.id }) { saved ->
                        OutlinedCard(onClick = { workspace.open(saved.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(saved.title); Text("${saved.turns.count { it.role == "user" }} local turns • ${if (saved.sourceId == null) "fresh chat" else "source saved"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.forks.isEmpty()) item { Text("Local chats stay separate from Hermes. Open an old chat’s More menu to fork it here.") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = fork.thinking, onCheckedChange = { workspace.configure(it, fork.brief) }, enabled = !state.busy && fork.pending == null && fork.remoteId == null)
                    Text("Thinking", Modifier.padding(start = 8.dp).weight(1f))
                    TextButton(onClick = { setup = !setup }) { Text(if (setup) "Hide details" else "Details") }
                }
                if (setup) {
                  Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    Text("${fork.source?.messages?.size ?: 0} source messages saved • memory ${if (fork.memory.syncedAt == 0L) "not synced" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(fork.memory.syncedAt))}", style = MaterialTheme.typography.bodySmall)
                    Text("Thinking is slower and capped. Stop at any time. Each reply uses recent local turns and relevant source excerpts.", style = MaterialTheme.typography.bodySmall)
                    if (fork.summary.isNotBlank()) Text("Compacted context: ${fork.summary}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = workspace::compactCurrent, enabled = !state.busy && fork.pending == null && fork.remoteId == null) {
                        Text(if (fork.compaction != null || (fork.source != null && !fork.sourcePrepared)) "Compact / resume preparation" else "Compact now")
                    }
                    OutlinedTextField(brief, { brief = it.take(12000) }, label = { Text("Optional offline brief") }, maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && fork.pending == null && fork.remoteId == null)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { workspace.configure(fork.thinking, brief) }, enabled = !state.busy && fork.pending == null && fork.remoteId == null) { Text("Save brief") }
                        TextButton(onClick = workspace::prepareCurrent, enabled = !state.busy && (fork.source != null || fork.summarizedTurns > 0)) { Text("Index saved chat") }
                        TextButton(onClick = workspace::refreshMemory, enabled = !state.busy && fork.pending == null && fork.remoteId == null) { Text("Refresh memory") }
                        TextButton(onClick = onSetup, enabled = !state.busy) { Text("Model setup") }
                        TextButton(onClick = { review = true }, enabled = !state.busy && fork.turns.isNotEmpty() && fork.remoteId == null) { Text(if (fork.pending == null) "Continue in Hermes" else "Retry handoff") }
                        fork.remoteId?.let { id -> TextButton(onClick = { onRemote(id) }) { Text("Open Hermes branch") } }
                        TextButton(onClick = { remove = true }, enabled = !state.busy && fork.pending == null) { Text("Delete local fork") }
                    }
                  }
                }
                if (fork.compaction != null && !state.busy) OutlinedButton(onClick = workspace::compactCurrent, enabled = fork.pending == null && fork.remoteId == null) {
                    Text("Resume compaction")
                }
                if (fork.source != null || fork.summarizedTurns > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search saved chat") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !state.busy)
                    TextButton(onClick = { workspace.search(query) }, enabled = !state.busy && query.isNotBlank()) { Text("Search") }
                }
                LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.searchResults, key = { "source:${it.id}" }) { hit ->
                        OutlinedCard { Column(Modifier.padding(12.dp)) { Text("[${hit.id}] ${hit.role}", style = MaterialTheme.typography.labelMedium); SelectionContainer { Text(hit.text) } } }
                    }
                    items(fork.turns, key = { it.id }) { turn ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (turn.role == "user") "You" else when (turn.status) { "complete" -> "Gemma"; "generating" -> "Gemma · writing…"; else -> "Gemma · incomplete" }, style = MaterialTheme.typography.labelMedium)
                            if (turn.thought.isNotEmpty()) {
                                var expanded by remember(turn.id) { mutableStateOf(false) }
                                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide thinking" else "Show thinking") }
                                if (expanded) SelectionContainer { Text(turn.thought, style = MaterialTheme.typography.bodySmall) }
                            }
                            SelectionContainer { Text(turn.text.ifEmpty { if (turn.status == "generating") "…" else "No final answer was produced." }) }
                            if (turn.tools.isNotEmpty()) Text("Used ${turn.tools.joinToString()}", style = MaterialTheme.typography.labelSmall)
                            if (turn.citations.isNotEmpty()) FlowRow {
                                turn.citations.forEach { id -> TextButton(onClick = { follow = false; workspace.readChunk(id) }, enabled = !state.busy) { Text("[$id]") } }
                            }
                        } }
                    }
                }
                OutlinedTextField(fork.draft, workspace::draft, label = { Text("Message Gemma on this phone") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !state.busy && fork.pending == null && fork.remoteId == null)
                fork.remoteId?.let { id ->
                    TextButton(onClick = { onRemote(id) }) { Text("Synced · open Hermes conversation") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state.busy) Button(onClick = workspace::stop) { Text("Stop") }
                    else Button(onClick = workspace::send, enabled = fork.draft.isNotBlank() && fork.pending == null && fork.remoteId == null && state.forkReview == null) { Text("Send locally") }
                }
            }
        }
    }
    state.forkReview?.let { warning ->
        AlertDialog(onDismissRequest = workspace::dismissForkReview, title = { Text("This will take a hot minute") },
            text = { Text("This chat has ${warning.tokens} tokens, longer than Gemma’s ${warning.contextTokens}-token context. Gemma will compact it aggressively in chunks on your phone. A long chat may take several minutes or more. Keep Bellpull open; you can stop and resume. The original stays saved and searchable.") },
            confirmButton = { TextButton(onClick = workspace::compactCurrent) { Text("Compact local fork") } },
            dismissButton = { TextButton(onClick = workspace::dismissForkReview) { Text("Later") } })
    }
    if (review && fork != null) AlertDialog(onDismissRequest = { review = false }, title = { Text("Continue in Hermes?") },
        text = { Text("Save ${fork.turns.count { it.role == "user" || it.status == "complete" }} local messages and compacted context into Hermes’s searchable chat history as a new conversation. The app will show one synced entry. Thinking and unfinished answers are omitted. This does not rewrite Hermes’s permanent memory notes. The original chat stays separate. No tools run during import.") },
        confirmButton = { TextButton(onClick = { review = false; workspace.handoff(onRemote) }) { Text("Create Hermes branch") } },
        dismissButton = { TextButton(onClick = { review = false }) { Text("Cancel") } })
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Delete this local fork?") },
        text = { Text("This removes its local messages and search index. Server chats and the saved source copy remain.") },
        confirmButton = { TextButton(onClick = { remove = false; workspace.delete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
}
