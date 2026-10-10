package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.praxisog.hermes.model.Artifact

@Composable
internal fun ConversationArtifactsDialog(artifacts: List<Artifact>, onDownload: (Artifact) -> Unit,
    onView: (Artifact) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(20.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.72f)
            .testTag("conversation-artifacts"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("View artifacts", style = MaterialTheme.typography.titleLarge)
                Text("Files in this conversation", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (artifacts.isEmpty()) item { Text("No saved artifacts in this conversation yet.", style = MaterialTheme.typography.bodyMedium) }
                    items(artifacts.sortedByDescending { it.createdAt }, key = { it.id }) { ArtifactRow(it, onDownload, onView) }
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
