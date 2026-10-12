package org.praxisog.hermes

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

internal class SpeechLab(context: Context, create: (SpeechModel, File) -> LocalSpeechEngine) {
    private val app = context.applicationContext
    val models = speechCatalog(app.assets.open("speech-models.json").bufferedReader().use { it.readText() })
    val files = SpeechModelFiles(app)
    private val settings = SpeechExperimentSettings(app)
    val runtime = SpeechModelRuntime(files::verify) { model -> create(model, files.directory(model)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var selectedId by mutableStateOf(settings.selectedModel); private set
    var localVoice by mutableStateOf(settings.localVoice); private set
    var installed by mutableStateOf(models.filter(files::installed).map { it.id }.toSet()); private set
    var partial by mutableStateOf(models.filter(files::hasPartial).map { it.id }.toSet()); private set
    var busy by mutableStateOf(false); private set
    var operation by mutableStateOf(""); private set
    var progress by mutableStateOf<Float?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var actualBackend by mutableStateOf<String?>(null); private set
    var measurements by mutableStateOf(emptyList<SpeechRunMeasurement>()); private set
    var imported by mutableStateOf<SpeechAudio?>(null); private set
    private var task: Job? = null
    val selected get() = models.first { it.id == selectedId }
    fun choose(model: SpeechModel) = perform("Loading ${model.label}") {
        selectedId = model.id; settings.select(model.id); actualBackend = null
        withContext(Dispatchers.IO) {
            runtime.close()
            runtime.withModel(model) { engine, _ -> engine.actualBackend }
        }.let { actualBackend = it }
    }
    fun useLocal(value: Boolean) {
        if (busy) return
        if (value && selected.id !in installed) { notice = "Download the selected local model first."; return }
        settings.useLocal(value); localVoice = value
        if (!value) perform("Unloading local model") { withContext(Dispatchers.IO) { runtime.close() }; actualBackend = null }
    }
    fun download(model: SpeechModel) = perform("Downloading ${model.label}") {
        files.download(model) { downloaded, total -> scope.launch { progress = downloaded.toFloat() / total } }
        refreshInstalled()
        notice = "${model.label} is installed and verified."
    }
    fun delete(model: SpeechModel) = perform("Deleting ${model.label}") {
        withContext(Dispatchers.IO) {
            if (runtime.loadedModel == model.id) runtime.close()
            files.delete(model)
        }
        if (runtime.loadedModel == null) actualBackend = null
        refreshInstalled()
        notice = if (localVoice && selectedId == model.id) "Local voice needs this model downloaded again before it can start." else "Model deleted."
    }
    fun importAudio(bytes: ByteArray) = perform("Reading benchmark WAV") {
        imported = withContext(Dispatchers.IO) { SpeechAudio.readWav(bytes) }
        notice = "Loaded ${formatSpeech(imported!!.seconds)} seconds of audio. Enter its verified transcript before measuring WER."
    }
    fun error(message: String) { notice = message }
    fun benchmark(reference: String, feed: SpeechFeed, lastWordEndMs: Double?, sustained: Boolean) = perform("Benchmarking ${selected.label}") {
        val audio = checkNotNull(imported) { "Import a WAV first." }
        check(reference.isNotBlank()) { "Enter the verified reference transcript first." }
        require(lastWordEndMs == null || (lastWordEndMs.isFinite() && lastWordEndMs in 0.0..audio.seconds * 1000)) {
            "Last spoken word must end within the imported recording."
        }
        val model = selected
        val benchmarker = SpeechBenchmarker(runtime, SpeechThermals(app))
        val output = File(File(app.noBackupFilesDir, "speech-benchmarks"), "${System.currentTimeMillis()}-${model.id}-${feed.name}.json")
        withContext(Dispatchers.IO) { runtime.close() }
        measurements = emptyList()
        val fixture = SpeechFixture("imported-wav", reference, audio, lastWordEndMs)
        suspend fun run(repetition: Int) {
            val result = benchmarker.measure(model, fixture, feed, repetition)
            measurements = measurements + result
            actualBackend = result.backend
            saveResults(output)
        }
        repeat(4) { run(it) }
        if (sustained) {
            operation = "Sustained test · stop at any time"
            val started = android.os.SystemClock.elapsedRealtime()
            var repetition = 4
            while (android.os.SystemClock.elapsedRealtime() - started < 180_000) {
                currentCoroutineContext().ensureActive()
                run(repetition++)
            }
        }
        notice = "Finished: one unloaded-model run and ${measurements.size - 1} warm runs." +
            if (lastWordEndMs == null) " No annotated last-word boundary; that latency is unavailable." else " Forced file-end finalizations are reported separately."
    }
    fun cancel() { task?.cancel(); notice = "Stopping the experiment…" }
    private fun refreshInstalled() {
        installed = models.filter(files::installed).map { it.id }.toSet()
        partial = models.filter(files::hasPartial).map { it.id }.toSet()
    }
    private fun perform(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; operation = label; notice = null; progress = null
        task = scope.launch {
            try { block() }
            catch (canceled: CancellationException) { notice = "Experiment stopped. Completed model files and results are kept."; throw canceled }
            catch (error: Exception) { notice = error.message ?: "Speech experiment failed." }
            finally {
                refreshInstalled()
                if (runtime.loadedModel == null) actualBackend = null
                busy = false; operation = ""; progress = null
            }
        }
    }
    private suspend fun saveResults(saved: File) = withContext(Dispatchers.IO) {
        val rows = measurements.map { result -> buildJsonObject {
            put("model", result.modelId); put("checkpoint", result.checkpoint); put("quantization", result.quantization)
            put("runtime", result.runtime); put("actual_backend", result.backend); put("fixture", result.fixture)
            put("feed", result.mode); put("repetition", result.repetition); put("load_ms", result.loadMs)
            put("audio_seconds", result.audioSeconds); put("elapsed_ms", result.elapsedMs)
            result.firstPartialMs?.let { put("first_partial_ms", it) }
            result.lastWordToFinalMs?.let { put("last_word_to_final_ms", it) }
            put("forced_finalization", result.forcedFinalization); put("premature_endings", result.prematureEndings)
            put("word_edits", result.wordEdits); put("reference_words", result.referenceWords)
            put("peak_pss_kb", result.peakPssKb); put("peak_rss_kb", result.peakRssKb)
            put("thermal_start", result.thermalStart); put("thermal_end", result.thermalEnd)
            put("transcript", result.transcript)
            result.audioSha256?.let { put("audio_sha256", it) }
        } }
        check(saved.parentFile!!.mkdirs() || saved.parentFile!!.isDirectory)
        val pending = File(saved.parentFile, "${saved.name}.tmp")
        pending.writeText(JsonArray(rows).toString())
        check(pending.renameTo(saved)) { "Could not save benchmark results." }
    }
}

internal fun formatSpeech(value: Double) = String.format(Locale.ROOT, "%.2f", value)

/** Bounded read works on minSdk 29 and cannot allocate an unbounded document. */
internal fun InputStream.readSpeechWav(): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (result.size() <= SpeechAudio.MAX_BYTES) {
        val count = read(buffer, 0, minOf(buffer.size, SpeechAudio.MAX_BYTES + 1 - result.size()))
        if (count < 0) break
        result.write(buffer, 0, count)
    }
    require(result.size() <= SpeechAudio.MAX_BYTES) { "WAV file is too large. Use audio shorter than one minute." }
    return result.toByteArray()
}

@Composable
internal fun SpeechExperiments(lab: SpeechLab, beforeExperiment: () -> Unit) {
    var reference by rememberSaveable { mutableStateOf("") }
    var feed by rememberSaveable { mutableStateOf(SpeechFeed.THROUGHPUT) }
    var boundary by rememberSaveable { mutableStateOf("") }
    var sustained by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readSpeechWav() }
                    ?: error("Could not open the WAV file.")
            } }.onSuccess(lab::importAudio).onFailure { lab.error(it.message ?: "Could not read audio.") }
        }
    }
    Text("Local speech models", style = MaterialTheme.typography.titleMedium)
    Text("English recognition runs on this device after download. Model choice is separate from its compute backend.", style = MaterialTheme.typography.bodySmall)
    lab.models.forEach { model ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.label, style = MaterialTheme.typography.titleSmall)
                val status = when (model.id) { in lab.installed -> "Installed"; in lab.partial -> "Partial download"; else -> "Not installed" }
                Text("${formatSpeech(model.downloadBytes / 1_000_000.0)} MB · $status", style = MaterialTheme.typography.bodySmall)
                Text("${model.checkpoint} · ${model.quantization} · MIT", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(lab.selectedId == model.id, { beforeExperiment(); lab.choose(model) },
                        enabled = !lab.busy && model.id in lab.installed,
                        label = { Text(if (lab.selectedId == model.id) "Selected" else "Select") })
                    if (model.id !in lab.installed) OutlinedButton(enabled = !lab.busy, onClick = { lab.download(model) }) { Text(if (model.id in lab.partial) "Retry download" else "Download") }
                    TextButton(enabled = !lab.busy && (model.id in lab.installed || model.id in lab.partial), onClick = { beforeExperiment(); lab.delete(model) }) { Text("Delete") }
                }
            }
        }
    }
    Text("Compute backend", style = MaterialTheme.typography.titleSmall)
    FlowRow { FilterChip(true, {}, label = { Text("CPU") }) }
    Text("Actual backend: ${lab.actualBackend ?: "No model loaded"}", style = MaterialTheme.typography.bodySmall)
    Text("GPU and TPU are not supported by this integration yet. No accelerator is selected or substituted automatically.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Benchmark", style = MaterialTheme.typography.titleMedium)
    Text("Feeds a WAV directly into recognition; does not use the speaker, microphone or Hermes. Uses one unloaded-model run and three warm runs. Pauses at severe thermal status.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !lab.busy, onClick = { picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) { Text("Import benchmark WAV") }
    lab.imported?.let { Text("${formatSpeech(it.seconds)} seconds · 16 kHz mono PCM16", style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(reference, { reference = it }, label = { Text("Verified reference transcript") },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !lab.busy)
    OutlinedTextField(boundary, { boundary = it }, label = { Text("Last spoken word ends at (ms, optional)") },
        supportingText = { Text("Use an annotated time in this recording. Leave blank if unknown.") },
        isError = boundary.isNotBlank() && boundary.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } != true,
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !lab.busy)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeechFeed.entries.forEach { mode -> FilterChip(feed == mode, { feed = mode }, enabled = !lab.busy, label = { Text(mode.label) }) }
    }
    Row {
        Checkbox(sustained, { sustained = it }, enabled = !lab.busy)
        Text("Then run a sustained test for at least three minutes", style = MaterialTheme.typography.bodySmall)
    }
    Button(enabled = !lab.busy && lab.imported != null && reference.isNotBlank() && lab.selectedId in lab.installed &&
        (boundary.isBlank() || boundary.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..lab.imported!!.seconds * 1000 } == true),
        onClick = { beforeExperiment(); lab.benchmark(reference, feed, boundary.toDoubleOrNull(), sustained) }) { Text("Run benchmark") }
    if (lab.busy) {
        Text(lab.operation)
        lab.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = lab::cancel) { Text("Stop experiment") }
    }
    lab.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    lab.measurements.forEach { result ->
        val wer = WordErrors(result.wordEdits, result.referenceWords).rate?.let { "${formatSpeech(it * 100)}%" } ?: "unavailable"
        Text("${if (result.repetition == 0) "Unloaded model" else "Warm ${result.repetition}"} · load ${formatSpeech(result.loadMs)} ms · process ${formatSpeech(result.elapsedMs)} ms · WER $wer", style = MaterialTheme.typography.bodySmall)
        result.firstPartialMs?.let { Text("First partial ${formatSpeech(it)} ms", style = MaterialTheme.typography.bodySmall) }
        result.lastWordToFinalMs?.let { Text("Last word → final ${formatSpeech(it)} ms${if (result.forcedFinalization) " · forced at file end" else ""}", style = MaterialTheme.typography.bodySmall) }
        if (result.mode == SpeechFeed.REALTIME.name) Text("${result.prematureEndings} early line endings · ${if (result.forcedFinalization) "forced finalization" else "natural finalization or unavailable"}", style = MaterialTheme.typography.bodySmall)
        Text("Peak sampled PSS ${result.peakPssKb / 1024} MiB · RSS ${result.peakRssKb / 1024} MiB", style = MaterialTheme.typography.bodySmall)
        Text(result.transcript, style = MaterialTheme.typography.bodySmall)
    }
    SettingsSection("Earmarked models", "Later experiments; not installed or selectable") {
        Text("IBM Granite Speech 5.0 TurboCTC\nWhisper Tiny, Base, Small, Medium, Large v3 Turbo\nQwen3-ASR 0.6B and 1.7B\nParakeet TDT 0.6B v3\nParakeet Realtime EOU 120M", style = MaterialTheme.typography.bodySmall)
        Text("Parakeet TDT has a published Pixel 10 TPU path. Realtime EOU needs separate standalone recognition and turn-detection validation; no sensitivity control is exposed before the runtime is verified.", style = MaterialTheme.typography.bodySmall)
    }
}
