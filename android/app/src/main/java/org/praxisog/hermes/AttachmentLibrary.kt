package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.*

internal fun recentArtifacts(items: List<Artifact>) = items.filterNot { it.deleted }
    .distinctBy { it.id }.sortedByDescending { it.createdAt }.take(8)

/** A bounded recent gallery; the complete searchable library is opened on demand. */
@Composable
internal fun AttachmentLibrary(items: List<Artifact>, sessions: List<Session>, height: Dp,
    error: String?, onRefresh: () -> Unit, onPreview: (Artifact) -> Unit, onSource: (String) -> Unit,
    storage: ArtifactStorage? = null, warnings: List<ArtifactWarning> = emptyList(),
    onDelete: ((Artifact) -> Unit)? = null, loading: Boolean = false,
    upload: (@Composable () -> Unit)? = null) {
    var search by rememberSaveable { mutableStateOf(false) }
    val recent = remember(items) { recentArtifacts(items) }
    Column(Modifier.fillMaxWidth().height(height).testTag("recent-files"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            upload?.invoke()
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { search = true }) { Text("Search") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis) }
        if (warnings.isNotEmpty()) TextButton(onClick = { search = true }) {
            Text("${warnings.size} file ${if (warnings.size == 1) "notice" else "notices"} · View", color = MaterialTheme.colorScheme.error)
        }
        if (recent.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (loading) "Loading files…" else if (error != null) "Files could not be loaded. Open Search to retry."
                else "No files yet.", style = MaterialTheme.typography.bodyMedium)
        } else LazyVerticalGrid(GridCells.Adaptive(145.dp), Modifier.weight(1f).testTag("recent-file-grid"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent, key = { it.id }) { item ->
                OutlinedCard(onClick = { onPreview(item) }, modifier = Modifier.fillMaxWidth().testTag("recent-file-${item.id}")) {
                    Column(Modifier.padding(6.dp)) {
                        if (LocalArtifactThumbnails.current != null && LocalImagePreviewSize.current != ImagePreviewSize.OFF && ArtifactThumbnails.eligible(item, allowPdf = true)) {
                            ArtifactThumbnail(item, onPreview, height = 76.dp, allowPdf = true)
                        } else Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                            Text(if (!item.available) "Unavailable" else item.name.substringAfterLast('.', "FILE").uppercase().take(8),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (search) BellpullDialog(onDismissRequest = { search = false }) {
        Surface(Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.88f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Box(Modifier.weight(1f)) {
                    AttachmentBrowser(items, sessions, error, onRefresh,
                        { search = false; onPreview(it) }, { search = false; onSource(it) }, storage, warnings, onDelete)
                }
                TextButton(onClick = { search = false }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}
