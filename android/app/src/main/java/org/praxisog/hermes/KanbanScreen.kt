package org.praxisog.hermes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.praxisog.hermes.model.*

@Composable internal fun KanbanScreen(workspace: KanbanWorkspace?, foreground: Boolean, onOpenSession: (String) -> Unit = {}, clock: () -> Long = { System.currentTimeMillis() }) {
    if (workspace == null) {
        Column(Modifier.padding(20.dp)) { Text("Tasks", style = MaterialTheme.typography.titleLarge); Text("Choose a Hermes connection to open its task boards.") }
        return
    }
    LaunchedEffect(workspace, foreground, workspace.supported) {
        if (foreground) while (true) { workspace.refresh(); delay(30_000) }
    }
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(foreground) { if (foreground) while (true) { now = clock(); delay(10_000) } }
    var queueOpen by remember { mutableStateOf(false) }
    if (queueOpen) AlertDialog(onDismissRequest = { queueOpen = false }, title = { Text("Pending task edits") },
        confirmButton = { TextButton(onClick = { queueOpen = false }) { Text("Close") } }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(workspace.state.pending, key = { it.mutation.operationId }) { item ->
                    Text("${item.board.name} · ${item.board.ownerName}", style = MaterialTheme.typography.titleSmall)
                    Text("${item.mutation.taskId} · ${item.mutation.action} · ${item.state}")
                    Text(item.problem ?: "Waiting for confirmation")
                    item.mutation.comment?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    if (workspace.state.boards.any { it.key == item.board.key }) TextButton(onClick = {
                        workspace.review(item); queueOpen = false
                    }) { Text("Open task") }
                    if (item.state in listOf("queued", "conflict")) TextButton(onClick = { workspace.discard(item.mutation.operationId) }) { Text("Discard edit") }
                }
            }
        })
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var chooseBoard by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                TextButton(onClick = { chooseBoard = true }, enabled = workspace.state.boards.isNotEmpty()) {
                    Text(workspace.board?.name ?: "Task boards", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = chooseBoard, onDismissRequest = { chooseBoard = false }) {
                    workspace.state.boards.forEach { board -> DropdownMenuItem(text = { Text("${board.name} · ${board.ownerName}") },
                        onClick = { chooseBoard = false; workspace.choose(board.slug) }) }
                }
            }
            TextButton(onClick = workspace::refresh, enabled = workspace.supported && !workspace.busy) { Text("Refresh") }
        }
        workspace.board?.let { Text("${it.ownerName} · ${if (it.archived) "Archived board" else "Board owner"}", style = MaterialTheme.typography.labelMedium) }
        Text(kanbanAge(workspace.snapshot?.observedAt, now, workspace.fresh), style = MaterialTheme.typography.bodySmall)
        if (workspace.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        workspace.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (workspace.state.pending.isNotEmpty()) TextButton(onClick = { queueOpen = true }) { Text("Pending edits (${workspace.state.pending.size})") }
        if (!workspace.supported && workspace.problem == null) Text("Connect a server with the Kanban plugin to refresh. Saved tasks remain available.")
        val snapshot = workspace.snapshot
        if (workspace.selectedTask != null) {
            TextButton(onClick = workspace::back) { Text("Back to board") }
            val task = workspace.task
            if (task == null) Text("Task details have not loaded. Refresh to try again.")
            else KanbanTaskDetails(workspace, task, onOpenSession, Modifier.weight(1f))
        } else if (snapshot == null) {
            Text(if (workspace.busy) "Loading tasks…" else "No saved task board yet.")
        } else {
            if (!snapshot.complete) Text("Showing ${snapshot.tasks.size} of ${snapshot.total} tasks; older tasks are outside this snapshot.", style = MaterialTheme.typography.bodySmall)
            var query by rememberSaveable(snapshot.board.key) { mutableStateOf("") }
            OutlinedTextField(query, { query = it }, label = { Text("Search loaded tasks") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!workspace.state.boardView, { workspace.view(false) }, label = { Text("List") })
                FilterChip(workspace.state.boardView, { workspace.view(true) }, label = { Text("Board") })
            }
            val tasks = snapshot.tasks.filter { query.isBlank() || listOf(it.title, it.id, it.assignee.orEmpty(), it.body).any { text -> text.contains(query, true) } }
            if (tasks.isEmpty()) Text(if (query.isBlank()) "This board has no tasks." else "No matching loaded tasks.")
            if (workspace.state.boardView) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    snapshot.columns.forEach { status ->
                        val column = tasks.filter { it.status == status }
                        Column(Modifier.width(270.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${status.replaceFirstChar { it.uppercase() }} · ${column.size}", style = MaterialTheme.typography.titleSmall)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(column, key = { it.id }) { task -> KanbanCard(task, workspace.pending(task.id)) { workspace.open(task.id) } }
                            }
                        }
                    }
                }
            } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(tasks, key = { it.id }) { task -> KanbanCard(task, workspace.pending(task.id)) { workspace.open(task.id) } }
            }
        }
    }
}

internal fun kanbanAge(observed: Long?, now: Long, fresh: Boolean): String {
    if (observed == null) return "Not synced yet"
    val seconds = ((now - observed) / 1000).coerceAtLeast(0)
    val age = when { seconds < 60 -> "${seconds}s"; seconds < 3600 -> "${seconds / 60}m"; else -> "${seconds / 3600}h" }
    return "${if (fresh) "Snapshot" else "Saved snapshot"} · $age ago"
}

@Composable private fun KanbanCard(task: KanbanTask, pending: KanbanPending?, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${task.status} · ${task.assignee ?: "Unassigned"}", style = MaterialTheme.typography.bodySmall)
            task.projectId?.let { Text("Project: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (pending != null) Text(if (pending.mutation.action == "move") "Move to ${pending.mutation.status} · ${pending.state}"
                else "Comment · ${pending.state}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun KanbanTaskDetails(workspace: KanbanWorkspace, task: KanbanTask, openSession: (String) -> Unit, modifier: Modifier) {
    val pending = workspace.pending(task.id)
    var editor by rememberSaveable(workspace.board?.key, task.id) { mutableStateOf(workspace.draft(task.id)) }
    LaunchedEffect(pending?.mutation?.operationId) { if (pending?.mutation?.action == "comment") editor = "" }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Text(task.title, style = MaterialTheme.typography.titleLarge)
            Text("${task.status} · ${task.assignee ?: "Unassigned"}")
            Text("Task ${task.id}", style = MaterialTheme.typography.bodySmall)
            task.projectId?.let { Text("Project $it", style = MaterialTheme.typography.bodySmall) }
            if (task.body.isNotEmpty()) Text(task.body)
            task.sessionId?.let { TextButton(onClick = { openSession(it) }) { Text("Open conversation") } }
        }
        if (workspace.detail == null) item { Text("Showing the saved task excerpt. Refresh to load full details.") }
        if (!task.detailsComplete) item { Text("Details are bounded; older entries or longer text are not included.") }
        if (pending != null) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${pending.mutation.action.replaceFirstChar { it.uppercase() }} · ${pending.state}", style = MaterialTheme.typography.titleSmall)
                pending.mutation.comment?.let { Text(it) }
                pending.mutation.status?.let { Text("Requested move: $it") }
                Text(pending.problem ?: "Saved on this phone; waiting for Hermes confirmation.", style = MaterialTheme.typography.bodySmall)
                if (pending.state in listOf("queued", "conflict")) {
                    Row {
                        TextButton(onClick = { workspace.discard(pending.mutation.operationId) }) { Text("Discard edit") }
                        if (pending.mutation.comment != null) TextButton(onClick = {
                            editor = pending.mutation.comment.orEmpty(); workspace.discard(pending.mutation.operationId, edit = true)
                        }) { Text("Edit comment") }
                    }
                    if (pending.state == "conflict") TextButton(onClick = { workspace.applyReviewed(pending.mutation.operationId) }, enabled = workspace.fresh && workspace.detail != null) { Text("Apply to current task") }
                } else Text("Delivery is unresolved. Refresh checks its receipt before any retry.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        if (workspace.board?.archived == false) item {
            if (pending == null && task.allowedMoves.isNotEmpty()) {
                Text("Move task", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    task.allowedMoves.forEach { status -> OutlinedButton(onClick = { workspace.move(task.id, status) }) { Text("Move to $status") } }
                }
            }
            if (task.status == "running") Text("Running work is controlled by its executor.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(editor, { editor = it.take(8000); workspace.draft(task.id, editor) }, label = { Text("Comment") },
                minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Button(onClick = { workspace.comment(task.id, editor) }, enabled = editor.isNotBlank() && pending == null && workspace.state.pending.size < 100) { Text("Add comment") }
        }
        if (task.result.isNotEmpty()) item { Text("Result", style = MaterialTheme.typography.titleMedium); Text(task.result) }
        item { Text("Comments", style = MaterialTheme.typography.titleMedium); if (task.comments.isEmpty()) Text("No comments in this snapshot.") }
        items(task.comments, key = { "comment-${it.id}" }) { comment -> Text("${comment.author}: ${comment.body}") }
        item { Text("Execution attempts", style = MaterialTheme.typography.titleMedium); if (task.runs.isEmpty()) Text("No attempts in this snapshot.") }
        items(task.runs, key = { "run-${it.id}" }) { run ->
            Text("Attempt ${run.id} · ${run.profile ?: "Unknown executor"} · ${run.status}")
            run.outcome?.let { Text(it) }; run.summary?.let { Text(it) }; run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (task.parents.isNotEmpty() || task.children.isNotEmpty()) item {
            Text("Dependencies", style = MaterialTheme.typography.titleMedium)
            (task.parents.map { "Depends on" to it } + task.children.map { "Needed by" to it }).forEach { (label, id) ->
                TextButton(onClick = { workspace.open(id) }) { Text("$label $id") }
            }
        }
        if (task.files.isNotEmpty()) item { Text("File references", style = MaterialTheme.typography.titleMedium) }
        items(task.files, key = { "file-${it.id}" }) { file -> Text("${file.filename} · ${file.device}\n${file.path}\n${file.size} bytes · hash ${file.hash ?: "unavailable"}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable internal fun KanbanToday(workspace: KanbanWorkspace, foreground: Boolean, open: (String) -> Unit) {
    LaunchedEffect(workspace, foreground, workspace.supported) { if (foreground) while (true) { workspace.refresh(); delay(30_000) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(foreground) { if (foreground) while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    val snapshot = workspace.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Open tasks", style = MaterialTheme.typography.titleMedium)
        Text(workspace.board?.let { "${it.name} · ${it.ownerName}" } ?: "Choose a board in Tasks", style = MaterialTheme.typography.bodySmall)
        Text(kanbanAge(snapshot?.observedAt, now, workspace.fresh), style = MaterialTheme.typography.bodySmall)
        workspace.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val tasks = snapshot?.tasks.orEmpty().filter { it.status !in listOf("done", "archived") }
        if (tasks.isEmpty()) Text(if (snapshot == null) "Tasks have not synced yet." else "No open tasks in this snapshot.")
        tasks.take(5).forEach { task -> KanbanCard(task, workspace.pending(task.id)) { open(task.id) } }
        if (tasks.size > 5 || snapshot?.complete == false) TextButton(onClick = { open("") }) { Text("Open full task board") }
    }
}
