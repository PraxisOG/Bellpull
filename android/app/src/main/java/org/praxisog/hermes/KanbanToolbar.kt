package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable internal fun KanbanToolbar(workspace: KanbanWorkspace?, sideBySide: Boolean, showList: Boolean, onNavigate: () -> Unit) {
    var boardsOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    ConversationToolbar(title = workspace?.board?.name ?: "Tasks", sideBySide = sideBySide, showList = showList,
        onNavigate = onNavigate, onNewSession = {}, creatingSession = false, hasSession = false, hasHistory = false,
        showContext = false, onContext = {}, onPrompts = {}, onSearch = {},
        primaryAction = {
            NavigationAction("New task", { workspace?.startForm() }, enabled = workspace?.canCreate == true,
                description = "New task", modifier = Modifier.testTag("toolbar-new-task")) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        }, menuContent = { dismiss ->
            DropdownMenuItem(text = { Text("Search tasks") }, enabled = workspace?.board != null,
                onClick = { dismiss(); workspace?.searchOpen = true; workspace?.back() })
            DropdownMenuItem(text = { Text("Refresh") }, enabled = workspace?.supported == true && !workspace.busy,
                onClick = { dismiss(); workspace?.refresh() })
            DropdownMenuItem(text = { Text("New task") }, enabled = workspace?.canCreate == true,
                onClick = { dismiss(); workspace?.startForm() })
            DropdownMenuItem(text = { Text("Switch board") }, enabled = !workspace?.state?.boards.isNullOrEmpty() && workspace?.busy == false,
                onClick = { dismiss(); boardsOpen = true })
            DropdownMenuItem(text = { Text("Board information") }, enabled = workspace?.board != null,
                onClick = { dismiss(); infoOpen = true })
        })
    if (boardsOpen && workspace != null) AlertDialog(onDismissRequest = { boardsOpen = false }, title = { Text("Switch board") },
        confirmButton = { TextButton(onClick = { boardsOpen = false }) { Text("Close") } }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(workspace.state.boards, key = { it.key }) { board ->
                    TextButton(onClick = { workspace.choose(board.slug); boardsOpen = false }) {
                        Text("${board.name} · ${board.ownerName}${if (board.archived) " · Archived" else ""}")
                    }
                }
            }
        })
    if (infoOpen && workspace != null) AlertDialog(onDismissRequest = { infoOpen = false }, title = { Text("Board information") },
        confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("Close") } }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                workspace.board?.let { board ->
                    Text(board.name); Text("Owner: ${board.ownerName}")
                    if (board.description.isNotBlank()) Text(board.description)
                    if (board.archived) Text("Archived · read only")
                }
                Text(kanbanAge(workspace.hierarchy?.observedAt ?: workspace.snapshot?.observedAt, System.currentTimeMillis(), workspace.fresh && workspace.hierarchyProblem == null))
                (workspace.hierarchy?.observedAt ?: workspace.snapshot?.observedAt)?.let { Text(DateFormat.getDateTimeInstance().format(Date(it))) }
                Text("Progress counts all descendants marked done; archived tasks are not completed. Containment does not schedule work.")
            }
        })
}
