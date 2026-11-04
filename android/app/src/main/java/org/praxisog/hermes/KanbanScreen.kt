package org.praxisog.hermes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    if (workspace.editorId != null) workspace.form?.let { form ->
        KanbanTaskEditor(form, workspace.editorId == "new", workspace.board?.name.orEmpty(), workspace.board?.ownerName.orEmpty(),
            workspace.state.pending.size < 100, workspace.problem, workspace::saveForm, workspace::submitForm,
            { workspace.closeForm() }, { workspace.closeForm(discard = true) },
            parentTasks = workspace.visibleTasks.filterNot { it.id.startsWith("draft_") }, canContain = workspace.board?.canContain == true)
    }
    if (queueOpen) AlertDialog(onDismissRequest = { queueOpen = false }, title = { Text("Pending task edits") },
        confirmButton = { TextButton(onClick = { queueOpen = false }) { Text("Close") } }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(workspace.state.pending, key = { it.mutation.operationId }) { item ->
                    Text("${item.board.name} · ${item.board.ownerName}", style = MaterialTheme.typography.titleSmall)
                    Text("${item.mutation.taskId} · ${item.mutation.action} · ${item.state}")
                    Text(item.problem ?: "Waiting for confirmation")
                    item.mutation.comment?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    item.mutation.fields?.let { Text("${it.title} · priority ${it.priority} · ${it.assignee ?: "Unassigned"}") }
                    if (workspace.state.boards.any { it.key == item.board.key }) TextButton(onClick = {
                        workspace.review(item); queueOpen = false
                    }) { Text("Open task") }
                    if (item.state in listOf("queued", "conflict")) TextButton(onClick = { workspace.discard(item.mutation.operationId) }) { Text("Discard edit") }
                }
            }
        })
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("List", "Board", "Tree").forEach { mode ->
                FilterChip(workspace.viewMode == mode, { workspace.view(mode); workspace.back() }, label = { Text(mode) })
            }
        }
        if (workspace.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        workspace.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (workspace.problem == null && !workspace.fresh) Text("Offline · saved tasks", style = MaterialTheme.typography.labelSmall)
        if (workspace.board?.archived == true) Text("Archived board · read only", style = MaterialTheme.typography.labelSmall)
        if (workspace.state.pending.isNotEmpty()) TextButton(onClick = { queueOpen = true }, contentPadding = PaddingValues(0.dp)) { Text("Pending edits (${workspace.state.pending.size})") }
        if (workspace.searchOpen) OutlinedTextField(workspace.query, { workspace.query = it.take(500) }, label = { Text("Search tasks") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                TextButton(onClick = { workspace.query = ""; workspace.searchOpen = false }) { Text("Clear") }
            })
        if (workspace.query.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filter: ${workspace.query}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { workspace.query = ""; workspace.searchOpen = false }) { Text("Clear filter") }
            if (workspace.searchOpen) TextButton(onClick = { workspace.searchOpen = false }) { Text("Hide") }
        }
        val snapshot = workspace.snapshot
        if (workspace.selectedTask != null) {
            TextButton(onClick = workspace::back) { Text("Back to board") }
            val task = workspace.task
            if (task == null) Text("Task details have not loaded. Refresh to try again.")
            else KanbanTaskDetails(workspace, task, onOpenSession, Modifier.weight(1f))
        } else if (snapshot == null && workspace.hierarchy == null) {
            Text(if (workspace.busy) "Loading tasks…" else "No saved task board yet. Use Refresh to load tasks.")
        } else {
            workspace.hierarchyProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (workspace.hierarchy == null) Text(
                if (workspace.busy && workspace.board?.hierarchy == true) "Loading hierarchy · ${workspace.hierarchyLoaded} tasks"
                else "Hierarchy not loaded · ${workspace.visibleTasks.size} saved tasks. Branch counts may be incomplete.",
                style = MaterialTheme.typography.bodySmall)
            val query = workspace.query
            val tasks = workspace.visibleTasks.filter { query.isBlank() || listOf(it.title, it.id, it.assignee.orEmpty(), it.body).any { text -> text.contains(query, true) } }
            if (workspace.viewMode == "Board" && tasks.isEmpty()) Text(if (query.isNotBlank()) "No matching loaded tasks."
                else if (workspace.hierarchy?.complete == true || snapshot?.complete == true) "This board has no tasks." else "No tasks loaded yet.")
            when (workspace.viewMode) {
                "Tree" -> KanbanTreeView(workspace, Modifier.weight(1f), onOpenSession)
                "Board" -> Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KANBAN_COLUMNS.forEach { status ->
                        val column = tasks.filter { it.status == status }
                        Column(Modifier.width(270.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${status.replaceFirstChar { it.uppercase() }} · ${column.size}", style = MaterialTheme.typography.titleSmall)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(column, key = { it.id }) { task -> KanbanCard(task, workspace.pending(task.id), onOpenSession) { workspace.open(task.id) } }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (tasks.isEmpty()) item { Text(if (query.isNotBlank()) "No matching loaded tasks." else if (workspace.hierarchy?.complete == true || snapshot?.complete == true) "This board has no tasks." else "No tasks loaded yet.") }
                    items(tasks, key = { it.id }) { task -> KanbanCard(task, workspace.pending(task.id), onOpenSession) { workspace.open(task.id) } }
                }
            }
        }
    }
}

@Composable private fun KanbanTreeView(workspace: KanbanWorkspace, modifier: Modifier, onOpenSession: (String) -> Unit) {
    val tree = remember(workspace.visibleTasks, workspace.hierarchy?.complete) { workspace.tree }
    val rows = remember(tree, workspace.expanded, workspace.query) { tree.rows(workspace.expanded, workspace.query) }
    Column(modifier) {
        Row {
            TextButton(onClick = { workspace.expandAll(true) }, enabled = workspace.query.isBlank()) { Text("Expand all") }
            TextButton(onClick = { workspace.expandAll(false) }, enabled = workspace.query.isBlank()) { Text("Collapse all") }
        }
        if (workspace.query.isNotBlank()) Text("Matching tasks with ancestor context", style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (rows.isEmpty()) item { Text(if (workspace.query.isNotBlank()) "No matching loaded tasks." else if (tree.complete) "This board has no tasks." else "Hierarchy not loaded. Refresh to load branches.") }
            items(rows, key = { it.first.id }) { (task, depth) ->
                val branch = task.subtaskCount > 0 || !tree.children[task.id].isNullOrEmpty()
                Row(Modifier.fillMaxWidth().padding(start = (minOf(depth, 4) * 12).dp), verticalAlignment = Alignment.CenterVertically) {
                    if (branch) IconButton(onClick = { workspace.expand(task.id) }, enabled = workspace.query.isBlank(), modifier = Modifier.testTag("expand-${task.id}")) {
                        Text(if (task.id in workspace.expanded || workspace.query.isNotBlank()) "▾" else "▸", modifier = Modifier.semantics {
                            this.contentDescription = "${if (task.id in workspace.expanded) "Collapse" else "Expand"} ${task.title}"
                        })
                    } else Spacer(Modifier.width(48.dp))
                    OutlinedCard(onClick = { workspace.open(task.id) }, modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(task.status + if (depth > 4) " · depth $depth" else "", style = MaterialTheme.typography.labelSmall)
                                if (branch) {
                                    val count = tree.progress[task.id] ?: (0 to 0)
                                    Text("${count.first}/${count.second} ${if (task.id in tree.incomplete) "loaded " else ""}subtasks done${if (task.id in tree.incomplete) " · incomplete" else ""}", style = MaterialTheme.typography.labelSmall)
                                    if (task.subtaskCount > tree.children[task.id].orEmpty().size) Text(if (tree.complete) "Some subtasks unavailable" else "Subtasks not loaded", style = MaterialTheme.typography.labelSmall)
                                }
                                if (task.id in tree.orphaned) Text(if (tree.complete) "Containing parent unavailable" else "Containing parent not loaded", style = MaterialTheme.typography.labelSmall)
                                workspace.pending(task.id)?.let { Text("${it.mutation.action} · ${it.state}", style = MaterialTheme.typography.labelSmall) }
                            }
                            KanbanChatButton(task, onOpenSession)
                        }
                    }
                }
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

@Composable private fun KanbanCard(task: KanbanTask, pending: KanbanPending?, onOpenSession: ((String) -> Unit)? = null, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("${task.status} · ${task.assignee ?: "Unassigned"}", style = MaterialTheme.typography.bodySmall)
                task.projectId?.let { Text("Project: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (pending != null) Text(when (pending.mutation.action) {
                    "move" -> "Move to ${pending.mutation.status} · ${pending.state}"
                    "create" -> "Pending creation · ${pending.state}"
                    "edit" -> "Field edits · ${pending.state}"
                    "contain" -> "Parent change · ${pending.state}"
                    else -> "Comment · ${pending.state}"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (onOpenSession != null) KanbanChatButton(task, onOpenSession)
        }
    }
}

@Composable private fun KanbanChatButton(task: KanbanTask, onOpenSession: (String) -> Unit) {
    task.sessionId?.takeIf { it.isNotBlank() }?.let { session ->
        TextButton(onClick = { onOpenSession(session) }, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag("kanban-chat-${task.id}").semantics { contentDescription = "Open chat for ${task.title}" }) {
            Text("Open chat", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun KanbanTaskDetails(workspace: KanbanWorkspace, task: KanbanTask, openSession: (String) -> Unit, modifier: Modifier) {
    val pending = workspace.pending(task.id)
    var chooseParent by remember(task.id) { mutableStateOf(false) }
    val tree = remember(workspace.visibleTasks) { workspace.tree }
    if (chooseParent) KanbanParentPicker(tree.parentCandidates(task.id),
        { workspace.reparent(task.id, it); chooseParent = false }, { chooseParent = false })
    var editor by rememberSaveable(workspace.board?.key, task.id) { mutableStateOf(workspace.draft(task.id)) }
    LaunchedEffect(pending?.mutation?.operationId) { if (pending?.mutation?.action == "comment") editor = "" }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Text(task.title, style = MaterialTheme.typography.titleLarge)
            Text("${task.status} · ${task.assignee ?: "Unassigned"}")
            Text(if (pending?.mutation?.action == "create") "Local draft · not yet confirmed by Hermes" else "Task ${task.id}", style = MaterialTheme.typography.bodySmall)
            Text("Priority ${task.priority}", style = MaterialTheme.typography.bodySmall)
            if (task.editable && workspace.detail != null && pending == null) TextButton(onClick = { workspace.startForm(task.id) }) { Text("Edit task") }
            if (workspace.board?.canContain == true && workspace.board?.archived == false && pending == null) {
                FlowRow {
                    TextButton(onClick = { workspace.startForm(containerId = task.id) }, enabled = workspace.canCreate) { Text("Add subtask") }
                    if (task.canReparent) TextButton(onClick = { chooseParent = true }) { Text("Change parent") }
                }
            }
            task.containerId?.let { parent -> TextButton(onClick = { workspace.open(parent) }) {
                Text("Subtask of ${workspace.visibleTasks.firstOrNull { it.id == parent }?.title ?: parent}")
            } }
            task.projectId?.let { Text("Project $it", style = MaterialTheme.typography.bodySmall) }
            if (task.body.isNotEmpty()) Text(task.body)
            task.sessionId?.let { TextButton(onClick = { openSession(it) }) { Text("Open conversation") } }
        }
        if (workspace.detail == null && pending?.mutation?.action != "create") item { Text("Showing the saved task excerpt. Refresh to load full details.") }
        if (!task.detailsComplete) item { Text("Details are bounded; older entries or longer text are not included.") }
        if (pending != null) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${pending.mutation.action.replaceFirstChar { it.uppercase() }} · ${pending.state}", style = MaterialTheme.typography.titleSmall)
                pending.mutation.comment?.let { Text(it) }
                pending.mutation.status?.let { Text("Requested move: $it") }
                if (pending.mutation.action == "contain") Text("Requested parent: ${pending.mutation.containerId ?: "Top level"}")
                pending.mutation.fields?.let { fields ->
                    Text("Requested title: ${fields.title}")
                    Text(fields.body, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Text("Assignee: ${fields.assignee ?: "Unassigned"} · priority ${fields.priority}")
                }
                Text(pending.problem ?: "Saved on this phone; waiting for Hermes confirmation.", style = MaterialTheme.typography.bodySmall)
                if (pending.state in listOf("queued", "conflict")) {
                    Row {
                        TextButton(onClick = { workspace.discard(pending.mutation.operationId) }) { Text("Discard edit") }
                        if (pending.mutation.comment != null) TextButton(onClick = {
                            editor = pending.mutation.comment.orEmpty(); workspace.discard(pending.mutation.operationId, edit = true)
                        }) { Text("Edit comment") }
                        if (pending.mutation.fields != null) TextButton(onClick = { workspace.editPending(pending) }) { Text("Edit draft") }
                    }
                    if (pending.state == "conflict") TextButton(onClick = { workspace.applyReviewed(pending.mutation.operationId) },
                        enabled = workspace.fresh && (workspace.detail != null || pending.mutation.action == "create")) {
                        Text(if (pending.mutation.action == "create") "Retry creation" else "Apply to current task")
                    }
                } else Text("Delivery is unresolved. Refresh checks its receipt before any retry.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        if (workspace.board?.archived == false && pending?.mutation?.action != "create") item {
            if (pending == null && task.allowedMoves.isNotEmpty()) {
                Text("Move task", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    task.allowedMoves.forEach { status -> OutlinedButton(onClick = { workspace.move(task.id, status) }) { Text("Move to $status") } }
                }
            }
            if (task.status == "running") Text("Running work is controlled by its executor.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(editor, { editor = it.kanbanTake(8000); workspace.draft(task.id, editor) }, label = { Text("Comment") },
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

@Composable internal fun KanbanTaskEditor(initial: KanbanFormDraft, creating: Boolean, board: String, owner: String,
    enabled: Boolean, error: String?, onChange: (KanbanFormDraft) -> Unit, onSave: (KanbanFormDraft) -> Unit,
    onDismiss: () -> Unit, onDiscard: () -> Unit, parentTasks: List<KanbanTask> = emptyList(), canContain: Boolean = false) {
    var form by remember(initial.taskId) { mutableStateOf(initial) }
    fun update(next: KanbanFormDraft) { form = next; onChange(next) }
    var chooseParent by remember { mutableStateOf(false) }
    if (chooseParent) KanbanParentPicker(parentTasks, { update(form.copy(containerId = it)); chooseParent = false }, { chooseParent = false })
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.semantics { testTagsAsResourceId = true }.imePadding().padding(20.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (creating) "New task" else "Edit task", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$board · $owner", style = MaterialTheme.typography.bodySmall)
                    if (creating) Text("Creates a task in triage. Work starts only when you schedule it in Hermes.", style = MaterialTheme.typography.bodySmall)
                    if (creating && canContain) TextButton(onClick = { chooseParent = true }) {
                        Text("Parent: ${form.containerId?.let { id -> parentTasks.firstOrNull { it.id == id }?.title ?: id } ?: "Top level"}")
                    }
                    OutlinedTextField(form.title, { update(form.copy(title = it.kanbanTake(500))) }, label = { Text("Task title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("kanban-title"))
                    OutlinedTextField(form.body, { update(form.copy(body = it.kanbanTake(16384))) }, label = { Text("Description") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("kanban-body"))
                    OutlinedTextField(form.assignee, { update(form.copy(assignee = it.take(64))) }, label = { Text("Assignee profile") }, singleLine = true,
                        supportingText = { Text("Leave blank for unassigned") }, modifier = Modifier.fillMaxWidth().testTag("kanban-assignee"))
                    OutlinedTextField(form.priority, { update(form.copy(priority = it.take(12))) }, label = { Text("Priority (higher first)") }, singleLine = true,
                        isError = form.priority.toIntOrNull() == null, modifier = Modifier.fillMaxWidth().testTag("kanban-priority"))
                    if (form.assignee.isNotBlank() && !kanbanSlug(form.assignee.trim().lowercase())) Text("Use a profile name with letters, numbers, hyphens or underscores.", color = MaterialTheme.colorScheme.error)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDiscard) { Text("Discard draft") }
                    TextButton(onClick = onDismiss) { Text("Keep draft") }
                    Button(onClick = { onSave(form) }, enabled = enabled && form.fields() != null) { Text(if (creating) "Create task" else "Save changes") }
                }
            }
        }
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
        val tasks = workspace.visibleTasks.filter { it.status !in listOf("done", "archived") }
        if (tasks.isEmpty()) Text(if (snapshot == null) "Tasks have not synced yet." else "No open tasks in this snapshot.")
        tasks.take(5).forEach { task -> KanbanCard(task, workspace.pending(task.id)) { open(task.id) } }
        if (tasks.size > 5 || snapshot?.complete == false) TextButton(onClick = { open("") }) { Text("Open full task board") }
    }
}

@Composable private fun KanbanParentPicker(tasks: List<KanbanTask>, choose: (String?) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.imePadding().padding(20.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.85f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Containing parent", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it.take(500) }, label = { Text("Find parent") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { choose(null) }) { Text("Top level (no parent)") }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(tasks.filter { it.title.contains(query, true) || it.id.contains(query, true) }, key = { it.id }) { task ->
                        TextButton(onClick = { choose(task.id) }, modifier = Modifier.fillMaxWidth()) { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Text("Choose from loaded tasks. Refresh to load the full board.", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}
