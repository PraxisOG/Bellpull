package org.praxisog.hermes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Session

@Composable
internal fun ServerUploadButton(workspace: Phase3Workspace, sessions: List<Session>, currentSession: String?) {
    var open by rememberSaveable(workspace.profileId) { mutableStateOf(false) }
    var destination by rememberSaveable(workspace.profileId) { mutableStateOf(currentSession) }
    var destinations by remember { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var targetProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && targetProfile == workspace.profileId) target?.let { workspace.addUris(it, uris, uploadOnly = true) }
        target = null; targetProfile = null
    }
    val choices = sessions.filterNot { it.hidden || it.archived || it.id == BellpullExample.ID }
    val selected = choices.firstOrNull { it.id == destination }
    val importing = (workspace.importing["uploads"] ?: 0) > 0
    val problem = workspace.errors["uploads"] ?: workspace.errors["upload-import"]
    TextButton(onClick = { open = true }) {
        Text(when {
            workspace.serverUploading -> "Uploading…"
            importing -> "Reading files…"
            problem != null -> "Upload issue"
            workspace.serverUploads.isNotEmpty() -> "Uploads (${workspace.serverUploads.size})"
            else -> "Upload files"
        })
    }
    if (open) BellpullDialog(onDismissRequest = { open = false }) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 620.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Upload to server", style = MaterialTheme.typography.titleLarge)
                Text("Save files under a conversation without sending a message.", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { destinations = true }, enabled = choices.isNotEmpty()) {
                        Text(selected?.title ?: selected?.id ?: "Choose conversation", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(destinations, { destinations = false }) {
                        choices.forEach { session -> DropdownMenuItem(text = {
                            Text(session.title ?: session.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }, onClick = { destination = session.id; destinations = false }) }
                    }
                }
                if (choices.isEmpty()) Text("Open or create a server conversation first.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    target = selected?.id; targetProfile = workspace.profileId
                    picker.launch(Phase3Workspace.ALLOWED.toTypedArray())
                }, enabled = selected != null && !importing && workspace.serverUploads.size < 8) { Text("Choose files to upload") }
                if (importing) Text("Reading selected files…", style = MaterialTheme.typography.bodySmall)
                if (workspace.serverUploadedCount > 0) Text("${workspace.serverUploadedCount} uploaded.", style = MaterialTheme.typography.bodySmall)
                workspace.errors["upload-import"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                workspace.errors["uploads"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(workspace.serverUploads, key = { it.id }) { file ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                Text(sessions.find { it.id == file.sessionId }?.title ?: file.sessionId, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                workspace.progress[file.id]?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                            }
                            TextButton(onClick = { workspace.remove(file.id) }, enabled = !workspace.serverUploading) { Text("Remove") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (workspace.serverUploading) TextButton(onClick = workspace::cancelServerUploads) { Text("Cancel upload") }
                    else if (workspace.serverUploads.isNotEmpty()) TextButton(onClick = workspace::uploadServerFiles, enabled = !importing) { Text("Retry uploads") }
                    TextButton(onClick = { open = false }) { Text("Close") }
                }
            }
        }
    }
}
