package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun RunControls(state: RunPresentation?, onRetry: () -> Unit, onApproval: (String, String) -> Unit,
    onAbandon: () -> Unit = {}) {
    if (state == null) return
    var review by remember(state.submission.requestId) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (review) AlertDialog(onDismissRequest = { review = false }, title = { Text("Review saved request") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text(state.submission.text)
            Spacer(Modifier.height(12.dp))
            Text("Stopping tracking does not stop work already accepted by Hermes. Check this conversation before sending again. An empty composer will recover this text; your newer draft will be preserved.")
            TextButton(onClick = {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Saved request", state.submission.text))
            }) { Text("Copy saved request") }
        } }, confirmButton = { TextButton(onClick = { onAbandon(); review = false }) { Text("Stop tracking") } },
        dismissButton = { TextButton(onClick = { review = false }) { Text("Keep tracking") } })
    val approval = state.approval
    if (approval != null) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().testTag("run-approval")) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hermes needs your approval", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    Text(approval.description ?: "Review this operation before allowing it.", style = MaterialTheme.typography.bodySmall)
                    approval.command?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if ("once" in approval.choices) Button(enabled = !state.checking,
                        onClick = { onApproval(approval.requestId, "once") }) { Text("Allow once") }
                    if ("deny" in approval.choices) OutlinedButton(enabled = !state.checking,
                        onClick = { onApproval(approval.requestId, "deny") }) { Text("Deny") }
                }
            }
        }
    }
    state.notice?.let { notice ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(notice, style = MaterialTheme.typography.bodySmall)
                if (state.busy) TextButton(onClick = onRetry, enabled = !state.checking) { Text("Check status") }
                if (state.submission.status == "review") TextButton(onClick = { review = true }) { Text("Review saved request") }
            }
        }
    }
}
