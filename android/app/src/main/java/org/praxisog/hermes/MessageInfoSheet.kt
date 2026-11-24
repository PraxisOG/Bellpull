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
import org.praxisog.hermes.model.ProviderFallback
import org.praxisog.hermes.model.RunModel

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
        ModelRouteEvidence(info.servedModel, info.modelFallback)
        if (!info.available && info.servedModel == null && info.modelFallback == null) Text("Per-turn stats are unavailable for this message.")
        else if (info.available) Text(if (info.estimatedDuration) "Duration is the saved prompt-to-reply interval, not measured compute time."
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

@Composable
internal fun ModelRouteEvidence(served: RunModel?, fallback: ProviderFallback?) {
    if (fallback != null) {
        Text("Provider fallback", style = MaterialTheme.typography.titleSmall)
        Text("${fallback.from.model} (${fallback.from.provider}) → ${fallback.to.model} (${fallback.to.provider})")
        Text(when (fallback.to.vision) {
            true -> "The fallback model advertises image input."
            false -> "The fallback model does not advertise image input."
            null -> "Image support for the fallback model is unknown."
        }, style = MaterialTheme.typography.bodySmall)
    }
    if (served != null) {
        Text("Model that answered", style = MaterialTheme.typography.titleSmall)
        Text("${served.model} (${served.provider})")
    }
}
