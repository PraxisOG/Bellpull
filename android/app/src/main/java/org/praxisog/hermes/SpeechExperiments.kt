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

internal class SpeechLab(context: Context, create: ((SpeechModel, File) -> LocalSpeechEngine)? = null) {
    private val app = context.applicationContext
    val models = speechCatalog(app.assets.open("speech-models.json").bufferedReader().use { it.readText() })
    val files = SpeechModelFiles(app)
    private val settings = SpeechExperimentSettings(app)
    val runtime = SpeechModelRuntime(files::verify) { model ->
        create?.invoke(model, files.directory(model)) ?: MoonshineSpeechEngine(model, files.directory(model), settings.tuning(model.id))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var selectedId by mutableStateOf(settings.selectedModel); private set
    var tuning by mutableStateOf(settings.tuning(selectedId)); private set
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
        selectedId = model.id; settings.select(model.id); tuning = settings.tuning(model.id); actualBackend = null
        withContext(Dispatchers.IO) {
            runtime.close()
            if (files.installed(model)) runtime.withModel(model) { engine, _ -> engine.actualBackend } else null
        }.let { actualBackend = it }
    }
    fun unload() = perform("Unloading local model") {
        withContext(Dispatchers.IO) { runtime.close() }; actualBackend = null
    }
    fun tune(value: SpeechTuning) = perform("Saving ${selected.label} settings") {
        withContext(Dispatchers.IO) { runtime.close() }
        settings.tune(selectedId, value); tuning = value; actualBackend = null
        notice = "Saved. The next load uses these settings."
    }
    fun voiceStartProblem(): String? = when {
        busy -> "Finish the speech experiment before starting voice."
        // The UI's installed snapshot may predate a deletion or an interrupted installation.
        !files.installed(selected) -> "Download ${selected.label} in Settings → Voice input → Experimental first."
        else -> null
    }
    fun voiceTranscriber(): VoiceTranscriber {
        voiceStartProblem()?.let { throw IllegalStateException(it) }
        val thermals = SpeechThermals(app)
        return LocalVoiceTranscriber(runtime, selected) { thermals.severe }
    }
    fun fallbackTranscriber(): VoiceTranscriber? {
        if (busy) return null
        val model = selected.takeIf(files::installed) ?: models.firstOrNull(files::installed) ?: return null
        val thermals = SpeechThermals(app)
        return LocalVoiceTranscriber(runtime, model) { thermals.severe }
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
        notice = if (selectedId == model.id) "Local voice needs this model downloaded again before it can start." else "Model deleted."
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
    fun recognitionFinished() {
        if (runtime.loadedModel == null) actualBackend = null
        else actualBackend = "CPU (ONNX Runtime)"
    }
    private fun perform(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; operation = label; notice = null; progress = null
        task = scope.launch {
            try { block() }
            catch (canceled: CancellationException) {
                // A native load may finish after cancellation; retire it on the same worker lock.
                withContext(NonCancellable + Dispatchers.IO) { runtime.close() }
                actualBackend = null
                notice = if (canceled.message?.contains("severe thermal") == true)
                    "Phone reached severe thermal status. Let it cool before continuing. Completed results are kept."
                else "Experiment stopped. Completed model files and results are kept."
                throw canceled
            }
            catch (error: Exception) { notice = error.message ?: "Speech experiment failed." }
            finally {
                refreshInstalled()
                if (runtime.loadedModel == null) actualBackend = null
                busy = false; operation = ""; progress = null
            }
        }
    }
    private suspend fun saveResults(saved: File) = withContext(Dispatchers.IO) {
        check(saved.parentFile!!.mkdirs() || saved.parentFile!!.isDirectory)
        val pending = File(saved.parentFile, "${saved.name}.tmp")
        pending.writeText(speechMeasurementsJson(measurements).toString())
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
internal fun LocalSpeechSettings(lab: SpeechLab, beforeChange: () -> Unit, enabled: Boolean = true) {
    var choosing by remember { mutableStateOf(false) }
    val model = lab.selected
    val available = enabled && !lab.busy
    Text("Local speech models", style = MaterialTheme.typography.titleMedium)
    Box {
        OutlinedButton(enabled = available, onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text(model.label) }
        DropdownMenu(choosing, { choosing = false }) {
            lab.models.forEach { item -> DropdownMenuItem(text = {
                Text("${item.label} · ${formatSpeech(item.downloadBytes / 1_000_000.0)} MB")
            }, onClick = { choosing = false; beforeChange(); lab.choose(item) }) }
        }
    }
    val installed = model.id in lab.installed
    val status = if (installed) "Installed" else if (model.id in lab.partial) "Partial download" else "Not installed"
    Text("${formatSpeech(model.downloadBytes / 1_000_000.0)} MB · $status", style = MaterialTheme.typography.bodySmall)
    Text("${model.checkpoint} · ${model.quantization} · MIT", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!installed) OutlinedButton(enabled = available, onClick = { beforeChange(); lab.download(model) }) {
            Text(if (model.id in lab.partial) "Retry download" else "Download model")
        } else OutlinedButton(enabled = available, onClick = { beforeChange(); lab.choose(model) }) { Text("Load model") }
        TextButton(enabled = available && lab.runtime.loadedModel != null, onClick = { beforeChange(); lab.unload() }) { Text("Unload") }
        TextButton(enabled = available && (installed || model.id in lab.partial), onClick = { beforeChange(); lab.delete(model) }) { Text("Delete model") }
    }
    Text("Compute backend", style = MaterialTheme.typography.titleSmall)
    FilterChip(true, {}, enabled = available, label = { Text("CPU") })
    Text("Actual backend: ${lab.actualBackend?.takeIf { lab.runtime.loadedModel != null } ?: "No model loaded"}", style = MaterialTheme.typography.bodySmall)
    Text("This SDK supports CPU only. Switching models unloads the previous model first.", style = MaterialTheme.typography.bodySmall)
    SettingsSection("Model settings", "Saved separately for each model") {
        var update by remember(model.id, lab.tuning) { mutableIntStateOf(lab.tuning.updateMs) }
        var threshold by remember(model.id, lab.tuning) { mutableFloatStateOf(lab.tuning.vadThreshold) }
        Text("Streaming update interval: ${formatSpeech(update / 1000.0)} s")
        Slider(update.toFloat(), { update = it.toInt() }, enabled = available, valueRange = 200f..2000f)
        Text("Minimum interval between partial decoding passes; stretches under load. Used by chunked recognition benchmarks.", style = MaterialTheme.typography.bodySmall)
        Text("Moonshine speech threshold: ${formatSpeech(threshold.toDouble())}")
        Slider(threshold, { threshold = it }, enabled = available, valueRange = .1f.. .9f)
        Text("Native voice-activity probability threshold; higher values require stronger speech evidence. This does not control Bellpull's end-of-turn pause.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = available, onClick = { beforeChange(); lab.tune(SpeechTuning(update, threshold)) }) { Text("Save model settings") }
    }
    if (lab.busy) {
        Text(lab.operation)
        lab.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = lab::cancel) { Text("Stop experiment") }
    }
    lab.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
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
    Text("Changing models or starting experiments ends any active voice session. Saved transcriptions stay in their conversation.", style = MaterialTheme.typography.bodySmall)
    LocalSpeechSettings(lab, beforeExperiment)
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

internal fun speechMeasurementsJson(measurements: List<SpeechRunMeasurement>): JsonArray = JsonArray(measurements.map { result -> buildJsonObject {
            put("model", result.modelId); put("checkpoint", result.checkpoint); put("quantization", result.quantization)
            put("runtime", result.runtime); put("actual_backend", result.backend); put("fixture", result.fixture)
            put("feed", result.mode); put("repetition", result.repetition); put("load_ms", result.loadMs)
            put("audio_seconds", result.audioSeconds); put("elapsed_ms", result.elapsedMs)
            result.nativeInitializationMs?.let { put("native_initialization_ms", it) }
            result.firstPartialMs?.let { put("first_partial_ms", it) }
            result.lastWordToFinalMs?.let { put("last_word_to_final_ms", it) }
            put("forced_finalization", result.forcedFinalization); put("premature_endings", result.prematureEndings)
            put("word_edits", result.wordEdits); put("reference_words", result.referenceWords)
            put("peak_pss_kb", result.peakPssKb); put("peak_rss_kb", result.peakRssKb)
            put("thermal_start", result.thermalStart); put("thermal_end", result.thermalEnd)
            put("transcript", result.transcript)
            result.audioSha256?.let { put("audio_sha256", it) }
        } })
