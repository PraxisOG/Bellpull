package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun VoiceOutputSettings(initial: VoiceConfiguration, lab: VoiceOutputLab, enabled: Boolean,
    canSave: Boolean = true, beforeChange: () -> Unit = {}, onSave: (VoiceConfiguration) -> Unit) {
    var output by remember(initial) { mutableStateOf(initial.output) }
    var rate by remember(initial) { mutableFloatStateOf(initial.rate) }
    var error by remember { mutableStateOf<String?>(null) }
    var seed by remember(initial.output.seed) { mutableStateOf(initial.output.seed.toString()) }
    val validSeed = seed.toLongOrNull()?.takeIf { it >= -1 }
    val editable = enabled && !lab.busy
    Text("Speech synthesis", style = MaterialTheme.typography.titleMedium)
    Text("Spoken answers and Read aloud use the selection saved for this server profile. All choices run on this device. Qwen and Pocket need a one-time model download.", style = MaterialTheme.typography.bodySmall)
    VoiceOutputEngine.entries.forEach { engine ->
        FilterChip(output.engine == engine, { output = output.copy(engine = engine) }, enabled = editable,
            label = { Text(engine.label) })
    }
    Text("Playback speed: ${"%.2f".format(Locale.ROOT, rate)}×")
    Slider(rate, { rate = it }, enabled = editable, valueRange = .7f..1.5f)
    if (output.engine == VoiceOutputEngine.POCKET) {
        Text("Backend: CPU · ONNX Runtime · 4 threads", style = MaterialTheme.typography.labelLarge)
        Text("Pocket is implemented but has not been tested on this phone. English only · Loona reference voice. GPU and TPU are unavailable. Android voice remains the recommended default until testing.", style = MaterialTheme.typography.bodySmall)
        Text("Generation steps: ${output.pocketSteps}")
        Slider(output.pocketSteps.toFloat(), { output = output.copy(pocketSteps = it.toInt()) }, enabled = editable, valueRange = 1f..10f, steps = 8)
        Text("More flow steps cost more processing time; compare voice quality with Preview.", style = MaterialTheme.typography.bodySmall)
        Text("Temperature: ${"%.2f".format(Locale.ROOT, output.pocketTemperature)}")
        Slider(output.pocketTemperature, { output = output.copy(pocketTemperature = it) }, enabled = editable, valueRange = 0f..1.5f)
        Text("Decoder chunk: ${output.pocketChunkFrames} frames")
        Slider(output.pocketChunkFrames.toFloat(), { output = output.copy(pocketChunkFrames = it.toInt()) }, enabled = editable, valueRange = 1f..30f, steps = 28)
        Text("Smaller decoder chunks deliver shorter audio blocks. This runtime generates a phrase's latents before decoding audio, so chunk size alone does not remove the initial wait.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(seed, { seed = it }, label = { Text("Seed (-1 = random)") }, enabled = editable,
            singleLine = true, isError = validSeed == null || validSeed > Int.MAX_VALUE, modifier = Modifier.fillMaxWidth())
    }
    if (output.engine != VoiceOutputEngine.SYSTEM) {
        Text("Volume: ${(output.volume * 100).toInt()}%")
        Slider(output.volume, { output = output.copy(volume = it) }, enabled = editable)
    }
    if (output.engine == VoiceOutputEngine.QWEN) {
        Text("Backend: CPU · GGML", style = MaterialTheme.typography.labelLarge)
        Text("Pixel 10 Pro Fold CPU measurements: roughly 7–8 seconds of processing per second of speech; audio can have long gaps. This is an experiment, not yet a fast conversational voice.", style = MaterialTheme.typography.bodySmall)
        Text("GPU and TPU are unavailable in this build. This 0.6B model supports preset speakers, not style or emotion instructions.", style = MaterialTheme.typography.bodySmall)
        OutputChoice("Speaker", output.speaker, VoiceOutputConfiguration.speakers, editable) { output = output.copy(speaker = it) }
        Text("Ryan and Aiden are the English preset voices.", style = MaterialTheme.typography.bodySmall)
        OutputChoice("Language", output.language, VoiceOutputConfiguration.languages, editable) { output = output.copy(language = it) }
        SettingsSection("Advanced sampling", "Generation controls; playback speed is separate") {
            Text("Temperature: ${"%.2f".format(Locale.ROOT, output.temperature)} (0 = greedy)")
            Slider(output.temperature, { output = output.copy(temperature = it) }, enabled = editable, valueRange = 0f..1.5f)
            Text("Top-p: ${"%.2f".format(Locale.ROOT, output.topP)} (1 = no cutoff)")
            Slider(output.topP, { output = output.copy(topP = it) }, enabled = editable, valueRange = .1f..1f)
            Text("Top-k: ${output.topK} (0 = no cutoff)")
            Slider(output.topK.toFloat(), { output = output.copy(topK = it.toInt()) }, enabled = editable, valueRange = 0f..100f, steps = 99)
            Text("Repetition penalty: ${"%.2f".format(Locale.ROOT, output.repetition)}")
            Slider(output.repetition, { output = output.copy(repetition = it) }, enabled = editable, valueRange = 1f..1.5f)
            OutlinedTextField(seed, { seed = it },
                label = { Text("Seed (-1 = random)") }, enabled = editable, singleLine = true,
                isError = validSeed == null, modifier = Modifier.fillMaxWidth())
            Text("These controls affect the Talker sampler. The acoustic sub-talker uses upstream defaults (temperature 0.9, top-k 50, top-p 1).", style = MaterialTheme.typography.bodySmall)
        }
    }
    val validControls = validSeed != null && (output.engine != VoiceOutputEngine.POCKET || validSeed <= Int.MAX_VALUE)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = editable && canSave && validControls && lab.isInstalled(output.engine), onClick = {
            runCatching {
                beforeChange(); lab.unload()
                onSave(initial.copy(rate = rate, output = output.copy(seed = checkNotNull(validSeed)).validated()))
            }.onFailure { error = it.message }
        }) { Text("Save voice output") }
        OutlinedButton(enabled = editable && validControls && lab.isInstalled(output.engine), onClick = {
            beforeChange(); lab.sample(initial.copy(rate = rate, output = output.copy(seed = checkNotNull(validSeed))))
        }) { Text("Preview") }
    }
    if (!canSave) Text("Select a server profile to save a voice selection.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    SettingsSection("Qwen model", "859.85 MB · ${if (lab.installed) "Installed" else if (lab.partial) "Partial download" else "Not installed"}") {
        Text(QwenModel.label, style = MaterialTheme.typography.titleSmall)
        Text("${QwenModel.checkpoint} · Q4_K_M\nTalker 604.88 MB + shared audio tokenizer 254.97 MB. Apache 2.0 weights; SHA-256 verified. ${QwenModel.runtime}.", style = MaterialTheme.typography.bodySmall)
        Text(if (lab.loaded) "Loaded · actual backend CPU" else "Not loaded", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!lab.installed) Button(enabled = editable, onClick = { beforeChange(); lab.download() }) { Text(if (lab.partial) "Retry download" else "Download Qwen") }
            if (lab.installed && !lab.loaded) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.load() }) { Text("Load model") }
            if (lab.loaded) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.unload() }) { Text("Unload model") }
            if (lab.installed || lab.partial) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.delete() }) { Text("Delete model") }
        }
    }
    SettingsSection("Pocket model · experimental", "${"%.2f".format(Locale.ROOT, PocketModel.model.bytes / 1_000_000.0)} MB · ${if (lab.pocketInstalled) "Installed" else if (lab.pocketPartial) "Partial download" else "Not installed"}") {
        Text(PocketModel.checkpoint, style = MaterialTheme.typography.titleSmall)
        Text("INT8 main/flow/decoder; FP32 encoder/text conditioner. ${PocketModel.runtime}. CPU only. All files SHA-256 verified before load.", style = MaterialTheme.typography.bodySmall)
        Text("Model/reference audio: CC BY 4.0, Kyutai / sherpa-onnx conversion by csukuangfj2. Uses the bundled Loona reference sample, without custom voice import.", style = MaterialTheme.typography.bodySmall)
        Text(if (lab.pocketLoaded) "Loaded · actual backend CPU" else "Not loaded", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!lab.pocketInstalled) Button(enabled = editable, onClick = { beforeChange(); lab.download(VoiceOutputEngine.POCKET) }) { Text(if (lab.pocketPartial) "Retry Pocket download" else "Download Pocket") }
            if (lab.pocketInstalled && !lab.pocketLoaded) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.load(VoiceOutputEngine.POCKET) }) { Text("Load Pocket") }
            if (lab.pocketLoaded) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.unload() }) { Text("Unload Pocket") }
            if (lab.pocketInstalled || lab.pocketPartial) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.delete(VoiceOutputEngine.POCKET) }) { Text("Delete Pocket") }
        }
        Text("Loading a model unloads the previous output model. Phone benchmarks and listening tests are pending.", style = MaterialTheme.typography.bodySmall)
    }
    if (lab.busy) {
        if (lab.progress != null) LinearProgressIndicator(progress = { lab.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = lab::cancel) { Text("Stop voice output operation") }
    }
    lab.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("Local output receives phrases as Hermes replies. Qwen synthesizes streaming PCM; Pocket decodes chunks after each phrase’s latent generation. Phone CPU speed determines latency; short activity announcements also need synthesis. Interruption cancels generation and clears queued audio. No server plugin update is needed.", style = MaterialTheme.typography.bodySmall)
    SettingsSection("Models to try later", "A short list; not installed or selectable") {
        Text("• Qwen3-TTS 1.7B CustomVoice / VoiceDesign: natural-language style controls, higher memory cost.\n• Kokoro 82M: small multilingual preset voices.\n• Supertonic 3: lightweight multilingual output with speed and generation-step controls.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun OutputChoice(label: String, selected: String, values: List<String>, enabled: Boolean, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { open = true }) { Text("$label: $selected") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { open = false; choose(value) }) }
        }
    }
}
