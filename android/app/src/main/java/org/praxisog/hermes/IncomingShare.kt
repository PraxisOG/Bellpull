package org.praxisog.hermes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Session

internal data class IncomingShare(val text: String, val uris: List<Uri>) {
    companion object {
        const val CONSUMED = "org.praxisog.hermes.SHARE_CONSUMED"
        @Suppress("DEPRECATION")
        fun from(intent: Intent?): IncomingShare? {
            if (intent?.getBooleanExtra(CONSUMED, false) == true) return null
            if (intent?.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
            val files = if (intent?.action == Intent.ACTION_SEND_MULTIPLE)
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            val clips = intent?.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
            val uris = (files + clips).distinct().filter { it.scheme == "content" }.take(8)
            val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(100_000)
            return IncomingShare(text, uris).takeIf { it.text.isNotBlank() || it.uris.isNotEmpty() }
        }
    }
}

@Composable
internal fun IncomingShareDialog(share: IncomingShare, sessions: List<Session>, connected: Boolean,
    busy: Boolean, error: String?, onSelect: (String?) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Add to a draft") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${share.uris.size} file(s)" + if (share.text.isNotBlank()) " · ${share.text.take(100)}" else "", maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("Review in the conversation before sending.", style = MaterialTheme.typography.bodySmall)
            if (connected) {
                TextButton(onClick = { onSelect(null) }, enabled = !busy) { Text("New conversation") }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(sessions, key = { it.id }) { session -> TextButton(onClick = { onSelect(session.id) }, enabled = !busy) {
                        Text(session.title ?: "Untitled conversation", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } }
                }
            } else Text("Connect a server in Settings to choose a conversation.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { if (!connected) TextButton(onClick = onSettings) { Text("Settings") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
