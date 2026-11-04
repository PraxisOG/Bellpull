package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.KanbanTask
import org.praxisog.hermes.model.Session

internal data class KanbanBoardChat(val sessionId: String, val tasks: List<KanbanTask>)

/** Use explicit task origins, including nested/completed tasks, independently of the task filter. */
internal fun kanbanBoardChats(tasks: List<KanbanTask>): List<KanbanBoardChat> {
    val seen = HashSet<String>()
    val links = linkedMapOf<String, MutableList<KanbanTask>>()
    for (task in tasks) {
        if (!seen.add(task.id)) continue
        val session = task.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        links.getOrPut(session) { mutableListOf() }.add(task)
    }
    return links.map { (id, linked) -> KanbanBoardChat(id, linked) }
}

@Composable
internal fun KanbanBoardChatButton(workspace: KanbanWorkspace?, sessions: List<Session>, onOpen: (String) -> Unit) {
    val board = workspace?.board
    var pickerOpen by rememberSaveable(board?.key) { mutableStateOf(false) }
    val hierarchy = workspace?.hierarchy
    val snapshot = workspace?.snapshot
    val tasks = hierarchy?.tasks ?: snapshot?.tasks.orEmpty()
    val chats = remember(tasks) { kanbanBoardChats(tasks) }
    val complete = hierarchy?.complete ?: snapshot?.complete ?: false
    val oneChat = chats.size == 1 && complete
    TextButton(onClick = { if (oneChat) onOpen(chats.single().sessionId) else pickerOpen = true }, enabled = board != null,
        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("kanban-board-chats")
            .semantics { contentDescription = if (oneChat) "Open board chat" else "Choose board chat" }) {
        Text(if (oneChat) "Chat" else "Chats", maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
    if (pickerOpen && board != null && workspace != null) AlertDialog(onDismissRequest = { pickerOpen = false },
        title = { Text("${board.name} chats") },
        confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("Close") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chats linked to tasks on this board.", style = MaterialTheme.typography.bodySmall)
                if (!complete) Text(if (workspace.busy) "Loading task links… Saved links are available below."
                    else "Only loaded task links are shown. Refresh to look for more.", style = MaterialTheme.typography.bodySmall)
                else if (!workspace.fresh || workspace.hierarchyProblem != null) Text("Showing saved task links.", style = MaterialTheme.typography.bodySmall)
                if (workspace.supported && (!complete || !workspace.fresh || workspace.hierarchyProblem != null))
                    TextButton(onClick = workspace::refresh, enabled = !workspace.busy) { Text("Refresh links") }
                if (chats.isEmpty()) Text(if (complete) "No chats are linked to this board's tasks yet."
                    else "No linked chats in the loaded tasks.", style = MaterialTheme.typography.bodyMedium)
                val titles = remember(sessions) { sessions.associate { it.id to it.title?.takeIf(String::isNotBlank) } }
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chats, key = { it.sessionId }) { chat ->
                        OutlinedCard(onClick = { pickerOpen = false; onOpen(chat.sessionId) },
                            modifier = Modifier.fillMaxWidth().testTag("board-chat-${chat.sessionId}")) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(titles[chat.sessionId] ?: chat.sessionId, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${chat.tasks.size} linked ${if (chat.tasks.size == 1) "task" else "tasks"} · " +
                                    chat.tasks.take(3).joinToString { it.title }, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        })
}
