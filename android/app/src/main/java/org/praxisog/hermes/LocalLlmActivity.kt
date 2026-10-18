package org.praxisog.hermes

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class LocalLlmActivity : ComponentActivity() {
    private val lab by lazy { LocalLlmLab.get(applicationContext) }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: "Imported model.gguf"
            lab.import(uri, name)
        }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val text = lab.state.value.report
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
                    .onFailure { android.util.Log.w("LocalLlmLab", "Could not export benchmark report") }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = BellpullSettings(this).value
        setContent {
            val state by lab.state.collectAsState()
            HermesTheme(preferences.appearance, preferences.palette, preferences.customAccent) {
                LocalLlmScreen(state, { importer.launch(arrayOf("*/*")) }, lab::select, lab::load, lab::unload,
                    lab::deleteSelected, lab::newChat, lab::draft, lab::send, lab::stop, lab::benchmark,
                    { exporter.launch("bellpull-local-llm-report.json") }, ::finish)
            }
        }
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) lab.background()
    }
}

@Composable
internal fun LocalLlmScreen(
    state: LocalLabState, onImport: () -> Unit = {}, onSelect: (String) -> Unit = {},
    onLoad: (Boolean) -> Unit = {}, onUnload: () -> Unit = {}, onDelete: () -> Unit = {},
    onNewChat: () -> Unit = {}, onDraft: (String) -> Unit = {}, onSend: () -> Unit = {}, onStop: () -> Unit = {},
    onBenchmark: (Boolean) -> Unit = {}, onExport: () -> Unit = {}, onClose: () -> Unit = {},
) {
    var controls by rememberSaveable { mutableStateOf(true) }
    var remove by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    // Appending a new row must not itself turn following off. Only user scrolling
    // changes the intent; returning to the bottom resumes it.
    LaunchedEffect(dragging, list.canScrollForward) {
        if (dragging) follow = !list.canScrollForward
        else if (!list.canScrollForward) follow = true
    }
    LaunchedEffect(state.messages.lastOrNull()?.text) {
        if (state.messages.isEmpty()) follow = true
        else if (follow) list.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Local chat lab", style = MaterialTheme.typography.titleLarge)
                    Text("Experimental • on this phone", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { controls = !controls }) { Text(if (controls) "Hide setup" else "Setup") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (controls) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("Import a GGUF instruction model. Models are copied to private storage. This lab never sends your words to Hermes. Chat lasts for this app process; backgrounding stops work and unloads the model.", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onImport, enabled = !state.busy) { Text("Import GGUF") }
                            TextButton(onClick = { remove = true }, enabled = !state.busy && state.selected != null) { Text("Delete model") }
                        }
                    }
                    itemsIndexed(state.models) { _, model ->
                        FilterChip(selected = state.selected == model.id, onClick = { onSelect(model.id) }, enabled = !state.busy,
                            label = { Text("${model.name} • %.2f GiB".format(model.bytes / (1024.0 * 1024 * 1024))) })
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onLoad(false) }, enabled = !state.busy && state.selected != null) { Text("Load CPU") }
                            OutlinedButton(onClick = { onLoad(true) }, enabled = !state.busy && state.gpuValidatedFor != null && state.models.any { it.id == state.selected && it.sha256 == state.gpuValidatedFor }) { Text("Load GPU") }
                            TextButton(onClick = onUnload, enabled = !state.busy && state.ready) { Text("Unload") }
                        }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onBenchmark(false) }, enabled = !state.busy && state.selected != null) { Text("CPU benchmark") }
                            OutlinedButton(onClick = { onBenchmark(true) }, enabled = !state.busy && state.selected != null) { Text("Compare CPU/GPU") }
                            TextButton(onClick = onExport, enabled = !state.busy && state.report.isNotEmpty()) { Text("Export report") }
                            TextButton(onClick = { results = true }, enabled = state.report.isNotEmpty()) { Text("View results") }
                        }
                        Text(state.validation, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.metrics.isNotBlank()) Text(state.metrics, style = MaterialTheme.typography.bodySmall)
            if (state.messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Local text experiment\n2,048-token context • 256-token replies\nNo tools, attachments or automatic fallback", style = MaterialTheme.typography.bodyMedium)
                }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.messages) { _, message ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (message.role == "user") "You" else "Local model", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(message.text.ifEmpty { "…" }) }
                            if (message.status.isNotEmpty() && message.status != "complete") Text(message.status, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            OutlinedTextField(value = state.draft, onValueChange = onDraft, modifier = Modifier.fillMaxWidth(),
                label = { Text("Message local model") }, enabled = !state.busy, minLines = 1, maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onNewChat, enabled = !state.busy) { Text("New chat") }
                if (state.busy) Button(onClick = onStop) { Text("Stop") }
                else Button(onClick = onSend, enabled = state.ready && state.draft.isNotBlank()) { Text("Send locally") }
            }
        }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Delete imported model?") },
        text = { Text("Removes Bellpull's private copy and clears this local chat. The original document stays in place.") },
        confirmButton = { TextButton(onClick = { remove = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
    if (results) LocalLlmResults(state.report) { results = false }
}

@Composable
internal fun LocalLlmResults(report: String, onClose: () -> Unit) {
    val summary = remember(report) { localLlmReportSummary(report) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Local inference results") },
        text = { SelectionContainer { Text(summary, Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = onClose) { Text("Close results") } })
}

internal fun localLlmReportSummary(report: String): String = runCatching {
    val root = JSONObject(report)
    buildString {
        appendLine(root.getString("model"))
        appendLine("Model SHA-256: ${root.getString("sha256")}")
        appendLine(if (root.isNull("gpu_exact_match")) "CPU benchmark" else if (root.getBoolean("gpu_exact_match")) "GPU matched repeated CPU token sequences." else "GPU failed validation. Use CPU.")
        if (root.has("error")) appendLine("Error: ${root.getString("error")}")
        appendLine("\nShort measurements; battery life and sustained heat remain unproven.")
        val runs = root.getJSONArray("runs")
        repeat(runs.length()) { index ->
            val run = runs.getJSONObject(index)
            appendLine("\n${index + 1}. ${run.getString("backend")} • ${run.getInt("offloaded_layers")} GPU layers")
            appendLine(run.getString("prompt"))
            appendLine("Answer: ${run.getString("text").ifBlank { "(empty)" }}")
            appendLine("Prompt %.1f tok/s • generation %.1f tok/s".format(run.getDouble("prompt_tps"), run.getDouble("generation_tps")))
            appendLine("%.0f MiB PSS • %s".format(run.getJSONObject("observation").getDouble("pss_mib"), run.getString("finish")))
        }
    }
}.getOrElse { "No readable benchmark report. Export the JSON for diagnosis." }
