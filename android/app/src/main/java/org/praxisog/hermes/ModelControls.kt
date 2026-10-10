package org.praxisog.hermes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.praxisog.hermes.model.*

@Composable
internal fun ModelControl(workspace: Phase3Workspace, sessionId: String?) {
    var picker by remember(sessionId) { mutableStateOf(false) }
    val selected = if (sessionId == null) workspace.defaultSelection else workspace.selections[sessionId]
    if (picker) ModelPicker(workspace.catalog, selected, workspace.errors[sessionId ?: "models"],
        sessionId == null, onRefresh = { workspace.refreshModels(sessionId) },
        onChoose = { value, done -> workspace.choose(sessionId, value, done) }, onDismiss = { picker = false })
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().clickable { picker = true }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (sessionId == null) "Default model for new chats" else "Model · change", style = MaterialTheme.typography.labelMedium)
            Text(selected?.model?.let(::compactModelName) ?: if (sessionId == null) "Server default" else "Loading…",
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            selected?.let { Text(it.provider + reasoningLabel(it), style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (sessionId == null) {
        Text("Applies to new chats from this profile on this device. Existing conversations and server jobs keep their choices.",
            style = MaterialTheme.typography.bodySmall)
        if (selected != null) TextButton(onClick = workspace::clearDefault) { Text("Use server default") }
    }
    workspace.errors[sessionId ?: "models"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

private fun reasoningLabel(value: ModelSelection): String = value.options.reasoning?.let {
    if (!it.enabled) " · Thinking off" else " · Thinking ${it.effort ?: "on"}"
} ?: " · Server reasoning default"

@Composable
internal fun ModelPicker(catalog: ModelCatalog?, selected: ModelSelection?, error: String?, defaults: Boolean,
    onRefresh: () -> Unit, onChoose: (ModelSelection, (Boolean) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var showUnavailable by remember { mutableStateOf(false) }
    var choice by remember(selected, catalog?.default) { mutableStateOf(selected ?: catalog?.default) }
    var saving by remember { mutableStateOf(false) }
    val selectedRoute = catalog?.models?.find { it.model == choice?.model && it.provider == choice?.provider }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(.88f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (defaults) "Default model" else "Conversation model", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, label = { Text("Find model or provider") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row { FilterChip(showUnavailable, { showUnavailable = !showUnavailable }, label = { Text("Show unconfigured") }) }
                if (catalog == null) {
                    Text("Model catalog unavailable", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRefresh) { Text("Retry") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(catalog?.models.orEmpty().filter { (it.configured || showUnavailable) &&
                        (it.model.contains(query, true) || it.provider.contains(query, true)) }, key = { "${it.provider}:${it.model}" }) { model ->
                        Surface(color = if (selectedRoute == model) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().clickable(enabled = model.configured && !saving) {
                                choice = ModelSelection(model.model, model.provider)
                            }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(compactModelName(model.model), style = MaterialTheme.typography.bodyMedium)
                                Text(model.provider + if (!model.configured) " · Not configured" else if (model.vision) " · Vision" else " · Vision unconfirmed",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                selectedRoute?.let { model ->
                    Text("Thinking", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(choice?.options?.reasoning == null, { choice = choice?.copy(options = ModelOptions()) }, label = { Text("Default") })
                        if (model.reasoning.canDisable) FilterChip(choice?.options?.reasoning?.enabled == false,
                            { choice = choice?.copy(options = ModelOptions(ReasoningChoice(enabled = false))) }, label = { Text("Off") })
                        if (model.reasoning.canEnable) FilterChip(choice?.options?.reasoning?.let { it.enabled && it.effort == null } == true,
                            { choice = choice?.copy(options = ModelOptions(ReasoningChoice(enabled = true))) }, label = { Text("On") })
                        model.reasoning.efforts.forEach { effort -> FilterChip(choice?.options?.reasoning?.effort == effort,
                            { choice = choice?.copy(options = ModelOptions(ReasoningChoice(effort = effort))) }, label = { Text(effort) }) }
                    }
                    if (!model.reasoning.canDisable && model.reasoning.efforts.isNotEmpty()) Text("This runtime does not support turning thinking off.", style = MaterialTheme.typography.labelSmall)
                    if (model.reasoning.note.isNotBlank()) Text(model.reasoning.note, style = MaterialTheme.typography.labelSmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                catalog?.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    TextButton(enabled = choice != null && selectedRoute?.configured == true && !saving, onClick = {
                        saving = true; onChoose(choice!!) { success -> saving = false; if (success) onDismiss() }
                    }) { Text(if (saving) "Saving…" else "Apply") }
                }
            }
        }
    }
}
