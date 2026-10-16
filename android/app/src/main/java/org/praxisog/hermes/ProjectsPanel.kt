package org.praxisog.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.praxisog.hermes.model.*
import java.util.UUID

@Composable
internal fun ProjectFilterItems(workspace: ProjectWorkspace, unread: Set<String>, running: Set<String>,
    onSelect: (String) -> Unit, onManage: () -> Unit) {
    Text("Projects", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
    listOf(ALL_PROJECT_CHATS to "All conversations", UNFILED_PROJECT_CHATS to "Unfiled").forEach { (id, name) ->
        DropdownMenuItem(text = { Text((if (workspace.selectedScope == id) "✓ " else "") + name) }, onClick = { onSelect(id) })
    }
    workspace.snapshot.projects.filterNot { it.archived }.forEach { project ->
        DropdownMenuItem(text = { Text((if (workspace.selectedScope == project.id) "✓ " else "") + project.name +
            indicatorText(workspace.projectIndicators(project.id, unread, running))) }, onClick = { onSelect(project.id) })
    }
    DropdownMenuItem(text = { Text("Manage projects") }, onClick = onManage)
}

private fun indicatorText(counts: Pair<Int, Int>) = buildString {
    if (counts.first > 0) append(" · ${counts.first} unread")
    if (counts.second > 0) append(" · ${counts.second} running")
}

@Composable
internal fun ProjectsPanel(workspace: ProjectWorkspace?, sessions: List<Session>, now: Double,
    unread: Set<String>, runLabels: Map<String, String>, running: Set<String>,
    onSelectScope: (String) -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit, onSearch: () -> Unit,
    onOrganize: (Session) -> Unit, onRetryCreation: () -> Unit, titleOnly: Boolean = false) {
    if (workspace == null) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Projects", style = MaterialTheme.typography.titleLarge)
            Text("Connect a Hermes server in Settings to organize conversations into projects.")
        }
        return
    }
    var editing by remember { mutableStateOf<ConversationProject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConversationProject?>(null) }
    var archived by remember { mutableStateOf(false) }
    var releaseCreation by remember { mutableStateOf(false) }
    val selected = workspace.selectedProject
    val visible = workspace.sessions(sessions)
    val unreadHere = workspace.unreadIds(visible, unread)
    val labels = workspace.runLabels(visible, runLabels)
    val ordered = visible.sortedWith(compareByDescending<Session> { workspace.snapshot.membership(it.id)?.pinned == true }
        .thenByDescending { it.lastActive ?: it.startedAt ?: 0.0 }.thenBy { it.id })
    LazyColumn(Modifier.fillMaxSize().testTag("projects-panel"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.name ?: "Projects", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = workspace::refresh, enabled = workspace.supported && !workspace.refreshing && !workspace.mutating) { Text("Refresh") }
            }
            if (workspace.refreshing || workspace.mutating) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!workspace.supported) Text("Update the Bellpull plugin on this server to use projects. Cached organization is read-only.",
                style = MaterialTheme.typography.bodySmall)
            else if (!workspace.fresh) Text("Showing cached organization. Reconnect and refresh to make changes.", style = MaterialTheme.typography.bodySmall)
            workspace.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            workspace.pendingChat?.let {
                Text("A project chat creation needs recovery. Retrying opens the same chat.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRetryCreation, enabled = workspace.canEdit) { Text("Retry chat creation") }
                TextButton(onClick = { releaseCreation = true }, enabled = !workspace.mutating) { Text("Stop tracking creation") }
            }
        }
        if (selected == null) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelectScope(ALL_PROJECT_CHATS) }) { Text("All conversations") }
                    OutlinedButton(onClick = { onSelectScope(UNFILED_PROJECT_CHATS) }) { Text("Unfiled") }
                    Button(onClick = { creating = true }, enabled = workspace.canEdit) { Text("New project") }
                }
                FilterChip(selected = archived, onClick = { archived = !archived }, label = { Text("Archived projects") })
            }
            val projects = workspace.snapshot.projects.filter { it.archived == archived }
            if (projects.isEmpty()) item { Text(if (archived) "No archived projects." else "Create a project to keep related conversations together.") }
            items(projects, key = { "project-${it.id}" }) { project ->
                Surface(onClick = { onSelectScope(project.id) }, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("project-${project.id}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProjectMark(project)
                            Text(project.name, style = MaterialTheme.typography.titleMedium)
                        }
                        if (project.description.isNotBlank()) Text(project.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        val count = workspace.snapshot.memberships.count { it.projectId == project.id }
                        Text("$count ${if (count == 1) "conversation" else "conversations"}" +
                            indicatorText(workspace.projectIndicators(project.id, unread, running)), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else {
            item {
                TextButton(onClick = { workspace.choose(ALL_PROJECT_CHATS) }) { Text("All projects") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectMark(selected)
                    if (selected.archived) Text("Archived project", style = MaterialTheme.typography.labelLarge)
                }
                Text(selected.description.ifBlank { "Add a short description to remember what this project is for." },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!selected.archived) Button(onClick = onNew, enabled = workspace.canEdit) { Text("New chat") }
                    OutlinedButton(onClick = onSearch) { Text("Search project") }
                    TextButton(onClick = { editing = selected }, enabled = workspace.canEdit) { Text("Edit project") }
                    TextButton(onClick = { workspace.save(selected.copy(archived = !selected.archived), false) {} }, enabled = workspace.canEdit) {
                        Text(if (selected.archived) "Restore" else "Archive")
                    }
                    TextButton(onClick = { deleting = selected }, enabled = workspace.canEdit) { Text("Delete project") }
                }
                Text("Pinned first · newest activity next", style = MaterialTheme.typography.labelMedium)
                if (visible.isEmpty()) Text("No conversations here. Move an existing chat into this project or start a new one.")
            }
            items(ordered, key = { it.id }) { session ->
                SessionRow(session, now, selected = false, unread = session.id in unreadHere, runLabel = labels[session.id],
                    titleOnly = titleOnly, pinned = workspace.snapshot.membership(session.id)?.pinned == true,
                    onOrganize = { onOrganize(session) }, onClick = { onOpen(session.id) })
            }
        }
    }
    if (creating || editing != null) {
        val initial = editing ?: remember { ConversationProject(UUID.randomUUID().toString().replace("-", ""), "") }
        ProjectEditor(initial, creating, workspace.canEdit, workspace.error,
            onSave = { workspace.save(it, creating) { creating = false; editing = null } },
            onDismiss = { if (!workspace.mutating) { creating = false; editing = null } })
    }
    deleting?.let { project -> AlertDialog(onDismissRequest = { if (!workspace.mutating) deleting = null },
        title = { Text("Delete ${project.name}?") },
        text = { Text("Removes the project folder. Its conversations return to Unfiled and keep their complete saved history.") },
        confirmButton = { TextButton(onClick = { workspace.delete(project.id) { deleting = null } }, enabled = workspace.canEdit) { Text("Delete project") } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !workspace.mutating) { Text("Cancel") } }) }
    if (releaseCreation) AlertDialog(onDismissRequest = { releaseCreation = false },
        title = { Text("Stop tracking chat creation?") },
        text = { Text("If Hermes already created the chat, it remains in All conversations. This only forgets the local recovery request; it does not delete a conversation or stop a run.") },
        confirmButton = { TextButton(onClick = { if (workspace.stopTrackingCreation()) releaseCreation = false }, enabled = !workspace.mutating) { Text("Stop tracking") } },
        dismissButton = { TextButton(onClick = { releaseCreation = false }) { Text("Cancel") } })
}

@Composable
private fun ProjectMark(project: ConversationProject) {
    val color = project.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        project.icon?.let { Text(when (it) { "code" -> "</>"; "home" -> "⌂"; "craft" -> "✂"; "book" -> "▤"; "star" -> "★"; else -> "▱" }) }
    }
}

@Composable
internal fun ProjectEditor(initial: ConversationProject, creating: Boolean, enabled: Boolean, error: String?,
    onSave: (ConversationProject) -> Unit, onDismiss: () -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var color by remember(initial.id) { mutableStateOf(initial.color) }
    var icon by remember(initial.id) { mutableStateOf(initial.icon) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.imePadding().padding(20.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
          Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (creating) "New project" else "Edit project", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Project name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("project-name"))
                OutlinedTextField(description, { description = it.take(1000) }, label = { Text("Description (optional)") }, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth())
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(null to "None", "#59091E" to "Maroon", "#24665C" to "Teal", "#315DA8" to "Blue", "#381B18" to "Brown", "#AD5B14" to "Orange").forEach { (value, label) ->
                        FilterChip(selected = color == value, onClick = { color = value }, label = { Text(label) })
                    }
                }
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(null, "folder", "code", "home", "craft", "book", "star").forEach { value ->
                        FilterChip(selected = icon == value, onClick = { icon = value }, label = { Text(value?.replaceFirstChar { it.uppercase() } ?: "None") })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onSave(initial.copy(name = name.trim(), description = description, color = color, icon = icon)) },
                    enabled = enabled && name.isNotBlank()) { Text("Save") }
            }
          }
        }
    }
}

@Composable
internal fun ProjectChatDialog(workspace: ProjectWorkspace, session: Session, onDismiss: () -> Unit) {
    val membership = workspace.snapshot.membership(session.id)
    val current = workspace.snapshot.projects.firstOrNull { it.id == membership?.projectId }
    AlertDialog(onDismissRequest = { if (!workspace.mutating) onDismiss() }, title = { Text("Organize conversation") },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.title ?: "Untitled conversation", style = MaterialTheme.typography.titleSmall)
                Text("Current project: ${current?.name ?: "Unfiled"}")
                if (!workspace.canEdit) Text("Reconnect and refresh projects to make changes.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { workspace.pin(session.id, membership?.pinned != true) }, enabled = workspace.canPin(session.id)) {
                    Text(if (membership?.pinned == true) "Unpin conversation" else "Pin conversation")
                }
                if (current == null && !workspace.globalPins) Text("Update the Bellpull server plugin to pin Unfiled conversations.", style = MaterialTheme.typography.bodySmall)
                Text("Move to", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    item { TextButton(onClick = { workspace.move(session.id, null, onDismiss) }, enabled = workspace.canEdit && current != null) { Text("Unfiled") } }
                    items(workspace.snapshot.projects.filterNot { it.archived }, key = { it.id }) { project ->
                        TextButton(onClick = { workspace.move(session.id, project.id, onDismiss) }, enabled = workspace.canEdit && project.id != current?.id) { Text(project.name) }
                    }
                }
                workspace.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !workspace.mutating) { Text("Close") } })
}
