package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalFallbackBanner(ready: Boolean, loading: Boolean, problem: String?, onLoad: () -> Unit,
    onSettings: () -> Unit, onFork: (() -> Unit)? = null) {
    Surface(color = if (ready) Color(0xFFFFE5A0) else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (ready) Color(0xFF392C00) else MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag(if (ready) "local-ready-banner" else "local-unavailable-banner")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(if (ready) "Local model ready · limited functionality" else if (loading) "Offline · loading local model…" else "Offline · local model unavailable",
                style = MaterialTheme.typography.labelLarge)
            if (!ready && !loading && problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready && !loading) TextButton(onClick = onLoad) { Text("Load / retry") }
                if (onFork != null) TextButton(onClick = onFork) { Text("Create local fork") }
                TextButton(onClick = onSettings) { Text("Local settings") }
            }
        }
    }
}

@Composable
internal fun LocalFallbackSettingsEditor(settings: LocalFallbackSettings, model: LocalLabState,
    onImport: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit,
    onAskHermes: (() -> Unit)? = null, askingHermes: Boolean = false, hermesName: String? = null, hermesError: String? = null) {
    val configuration = settings.value
    var prompt by rememberSaveable(configuration.systemPrompt) { mutableStateOf(configuration.systemPrompt) }
    val validPrompt = prompt.isNotBlank() && prompt.length <= LocalFallbackSettings.MAX_PROMPT_CHARS
    Text("Gemma 4 E2B · CPU", style = MaterialTheme.typography.titleMedium)
    Text(if (model.models.any { it.sha256 == LocalLlmLab.E2B_SHA }) "Model installed on this phone" else "Import Gemma 4 E2B Q4_0 to enable local fallback.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Load automatically when disconnected", Modifier.weight(1f))
        Switch(configuration.autoLoad, { settings.update(configuration.copy(autoLoad = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Thinking for new local chats", Modifier.weight(1f))
        Switch(configuration.thinking, { settings.update(configuration.copy(thinking = it)) })
    }
    Text("Context length (tokens)", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalFallbackSettings.contexts.forEach { size ->
            FilterChip(configuration.contextTokens == size, { settings.update(configuration.copy(contextTokens = size)) },
                enabled = !model.busy, label = { Text("${size / 1024}K (${String.format(java.util.Locale.US, "%,d", size)})") },
                modifier = Modifier.testTag("local-context-$size"))
        }
    }
    Text("Larger contexts take more RAM and longer to read on CPU. Chats compact automatically as they fill up. Original messages stay saved. Loading runs while Bellpull is open.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Local system prompt") },
        minLines = 4, maxLines = 8, isError = !validPrompt, modifier = Modifier.fillMaxWidth().testTag("local-system-prompt"),
        supportingText = { Text(when {
            prompt.isBlank() -> "Enter instructions or restore the default."
            prompt.length > LocalFallbackSettings.MAX_PROMPT_CHARS -> "Keep the prompt under 16,000 characters."
            prompt != configuration.systemPrompt -> "Unsaved changes"
            else -> "Saved · used on the next reply in all local chats on this phone"
        }) })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { settings.update(settings.value.copy(systemPrompt = prompt.trim())) },
            enabled = validPrompt && prompt != configuration.systemPrompt && !model.busy,
            modifier = Modifier.testTag("save-local-prompt")) { Text("Save prompt") }
        TextButton(onClick = { prompt = OfflinePrompt.DEFAULT_SYSTEM_PROMPT }, enabled = !model.busy) { Text("Restore default") }
    }
    Text("Paste your own instructions or a short profile from Hermes. The prompt shares the context budget with messages and replies. Bellpull adds instructions for the local tools that are available.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { onAskHermes?.invoke() }, enabled = onAskHermes != null && !askingHermes,
        modifier = Modifier.testTag("ask-hermes-local-prompt")) {
        Text(if (askingHermes) "Opening Hermes…" else "Ask Hermes for a short prompt")
    }
    Text(if (onAskHermes == null && !askingHermes) "Connect to Hermes to request a short prompt."
        else "Sends a request in a new chat${hermesName?.let { " on $it" }.orEmpty()}. Copy the reply, paste it above, then save. Your local prompt stays unchanged until you save it.",
        style = MaterialTheme.typography.bodySmall)
    hermesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onImport, enabled = !model.busy) { Text("Manage model files") }
        Button(onClick = onLoad, enabled = model.scanned && !model.busy) { Text("Load local model") }
        if (model.ready) TextButton(onClick = onUnload, enabled = !model.busy) { Text("Unload") }
    }
}
