package org.praxisog.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun MessageInfoContent(info: MessageInfo, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).testTag("message-info"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Message info", style = MaterialTheme.typography.titleLarge)
        info.durationSeconds?.let {
            InfoField(if (info.estimatedDuration) "Working duration (estimated)" else "Working duration", formatWorkingDuration(it))
        }
        info.inputTokens?.let { InfoField("Tokens read (input)", groupDigits(it)) }
        info.outputTokens?.let { InfoField("Tokens written (output)", groupDigits(it)) }
        if (!info.available) Text("Per-turn stats are unavailable for this message.")
        else Text(if (info.estimatedDuration) "Duration is the saved prompt-to-reply interval, not measured compute time."
            else "Elapsed run time includes tools and waiting. Token counts are server-reported totals for the whole turn.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
