package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A stable server status. Local work is always an explicit action. */
@Composable
internal fun ConnectionStatusBanner(health: ConnectionHealth, onRetry: () -> Unit,
    onSettings: () -> Unit, onLocal: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag("connection-status")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(when (health.phase) {
                ConnectionHealth.Phase.ATTENTION -> health.error?.toUserMessage() ?: "Check server settings."
                ConnectionHealth.Phase.OFFLINE -> "Server unavailable · retrying connection"
                else -> "Reconnecting to server…"
            }, style = MaterialTheme.typography.labelLarge)
            Text("Your chat stays on this server.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("Retry connection") }
                if (health.phase == ConnectionHealth.Phase.ATTENTION)
                    TextButton(onClick = onSettings) { Text("Server settings") }
                if (health.localEligible && onLocal != null)
                    TextButton(onClick = onLocal) { Text("Create local fork") }
            }
        }
    }
}
