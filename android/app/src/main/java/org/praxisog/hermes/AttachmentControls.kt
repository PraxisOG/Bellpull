package org.praxisog.hermes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.*

@Composable
internal fun AttachButton(workspace: Phase3Workspace, sessionId: String, onAttached: (String) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { values ->
        target?.let { workspace.addUris(it, values); if (values.isNotEmpty()) onAttached(it) }
        target = null
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) target?.let { id -> cameraUri?.let { workspace.addUris(id, listOf(Uri.parse(it))); onAttached(id) } }
        target = null; cameraUri = null
    }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp, 40.dp)
            .semantics { contentDescription = "Attach file or photo" }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Choose files or images") }, onClick = {
                menu = false; target = sessionId; picker.launch(Phase3Workspace.ALLOWED.toTypedArray())
            })
            DropdownMenuItem(text = { Text("Take photo") }, onClick = {
                menu = false; target = sessionId
                val file = ArtifactSharing.temporary(context, workspace.profileId, "photo.jpg")
                val uri = ArtifactSharing.uri(context, file); cameraUri = uri.toString()
                try { camera.launch(uri) }
                catch (_: android.content.ActivityNotFoundException) { workspace.errors[sessionId] = "No camera app is available." }
            })
        }
    }
}

@Composable
internal fun AttachmentStrip(workspace: Phase3Workspace, sessionId: String) {
    val files = workspace.filesFor(sessionId)
    val error = workspace.errors[sessionId]
    val importing = (workspace.importing[sessionId] ?: 0) > 0
    if (files.isEmpty() && error == null && !importing) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        if (importing) Text("Reading selected files…", style = MaterialTheme.typography.labelSmall)
        if (files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { file ->
                InputChip(selected = file.uploaded, onClick = {},
                    label = { Text(file.name + if (file.uploaded) " · Ready" else workspace.progress[file.id]?.let { " · ${(it * 100).toInt()}%" }.orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                    trailingIcon = { IconButton(onClick = { workspace.remove(file.id) }, modifier = Modifier.size(24.dp)
                        .semantics { contentDescription = "Remove ${file.name}" }) { Text("×") } })
            }
        }
        if (workspace.sending[sessionId] == true) Row {
            Text("Preparing attachments…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { workspace.cancel(sessionId) }, contentPadding = PaddingValues(2.dp)) { Text("Cancel upload") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun AttachmentBrowser(items: List<Artifact>, sessions: List<Session>, error: String?,
    onRefresh: () -> Unit, onPreview: (Artifact) -> Unit, onSource: (String) -> Unit,
    storage: ArtifactStorage? = null, warnings: List<ArtifactWarning> = emptyList(), onDelete: ((Artifact) -> Unit)? = null) {
    var order by rememberSaveable { mutableStateOf("Date") }
    var selectedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var filterMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Artifact?>(null) }
    Text("Attachments", style = MaterialTheme.typography.titleMedium)
    storage?.let { Text("${it.usedBytes / (1024 * 1024)} of ${it.quotaBytes / (1024 * 1024)} MiB used · Files kept until deleted",
        style = MaterialTheme.typography.bodySmall) }
    warnings.take(5).forEach { Text("${it.name}: ${it.message}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error) }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete file?") },
        text = { Text("Delete ${item.name} from the server? This removes its content from all conversations and branches. The history keeps an unavailable-file reference.") },
        confirmButton = { TextButton(onClick = { onDelete?.invoke(item); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            TextButton(onClick = { filterMenu = true }) { Text(if (selectedSession == null) "All conversations ▾" else "Conversation ▾") }
            DropdownMenu(filterMenu, { filterMenu = false }) {
                DropdownMenuItem(text = { Text("All conversations") }, onClick = { selectedSession = null; filterMenu = false })
                items.flatMap { listOf(it.sessionId) + it.sessionIds }.distinct().forEach { id -> DropdownMenuItem(
                    text = { Text(sessions.find { it.id == id }?.title ?: id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { selectedSession = id; filterMenu = false }) }
            }
        }
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("Date", "Name", "Type", "Size").forEach { label -> FilterChip(order == label, { order = label }, label = { Text(label) }) }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val visible = items.mapNotNull { item -> selectedSession?.let(item::forSession) ?: item.takeIf { selectedSession == null } }.let { values -> when (order) {
        "Name" -> values.sortedBy { it.name.lowercase() }; "Type" -> values.sortedBy { it.mime }
        "Size" -> values.sortedByDescending { it.size }; else -> values.sortedByDescending { it.createdAt }
    } }
    if (visible.isEmpty()) Text("No attachments yet.", style = MaterialTheme.typography.bodySmall)
    visible.forEach { item -> OutlinedCard(onClick = { onPreview(item) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.mime.substringAfterLast('/')} · ${(item.size + 1023) / 1024} KB · ${if (item.source == "agent") "Generated" else "Uploaded"}" +
                if (item.deleted) " · Deleted" else if (item.available) "" else " · Unavailable", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onSource(item.sessionId) }, contentPadding = PaddingValues(0.dp)) {
                Text(sessions.find { it.id == item.sessionId }?.title ?: "Open source conversation", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onDelete != null && !item.deleted) TextButton(onClick = { deleting = item }, contentPadding = PaddingValues(0.dp)) { Text("Delete file") }
        }
    } }
}
