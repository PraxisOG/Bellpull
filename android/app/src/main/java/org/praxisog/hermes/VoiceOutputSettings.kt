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
    Text("Spoken answers and Read aloud use the selection saved for this server profile. On-device Qwen and MOSS need a one-time model download; server offload uses your existing connection.", style = MaterialTheme.typography.bodySmall)
    VoiceOutputEngine.entries.forEach { engine ->
        FilterChip(output.engine == engine, { output = output.copy(engine = engine) }, enabled = editable,
            label = { Text(engine.label) })
    }
    Text("Playback speed: ${"%.2f".format(Locale.ROOT, rate)}×")
    if (output.engine == VoiceOutputEngine.SERVER_MOSS) {
        Text("The phone sends reply text to the Bellpull server over the existing profile connection. No model download or separate key is needed.",
            style = MaterialTheme.typography.bodySmall)
    }
    Slider(rate, { rate = it }, enabled = editable, valueRange = .7f..1.5f)
    if (output.engine == VoiceOutputEngine.MOSS) {
        Text("Backend: CPU · ONNX Runtime · 2 threads", style = MaterialTheme.typography.labelLarge)
        Text("Built-in voice: ${MossModel.voice}. MOSS-TTS-Nano has not been tested on this phone. GPU and TPU are unavailable.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(seed, { seed = it }, label = { Text("Seed (-1 = random)") }, enabled = editable,
            singleLine = true, isError = validSeed == null, modifier = Modifier.fillMaxWidth())
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
    val validControls = validSeed != null
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
    if (output.engine == VoiceOutputEngine.SERVER_MOSS) return
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
    SettingsSection("MOSS model · experimental", "${"%.2f".format(Locale.ROOT, MossModel.model.bytes / 1_000_000.0)} MB · ${if (lab.mossInstalled) "Installed" else if (lab.mossPartial) "Partial download" else "Not installed"}") {
        Text(MossModel.model.label, style = MaterialTheme.typography.titleSmall)
        Text("16 model/tokenizer files · ONNX Runtime CPU. All files SHA-256 verified before load. Built-in voice: ${MossModel.voice}.", style = MaterialTheme.typography.bodySmall)
        Text(if (lab.mossLoaded) "Loaded · actual backend CPU" else "Not loaded", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!lab.mossInstalled) Button(enabled = editable, onClick = { beforeChange(); lab.download(VoiceOutputEngine.MOSS) }) { Text(if (lab.mossPartial) "Retry MOSS download" else "Download MOSS") }
            if (lab.mossInstalled && !lab.mossLoaded) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.load(VoiceOutputEngine.MOSS) }) { Text("Load MOSS") }
            if (lab.mossLoaded) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.unload() }) { Text("Unload MOSS") }
            if (lab.mossInstalled || lab.mossPartial) OutlinedButton(enabled = editable, onClick = { beforeChange(); lab.delete(VoiceOutputEngine.MOSS) }) { Text("Delete MOSS") }
            OutlinedButton(enabled = editable && lab.mossInstalled && validControls, onClick = {
                beforeChange()
                lab.sample(initial.copy(rate = rate, output = output.copy(engine = VoiceOutputEngine.MOSS, seed = checkNotNull(validSeed))))
            }) { Text("Preview MOSS · ${MossModel.voice}") }
        }
        Text("Loading a model unloads the previous output model. Phone benchmarks and listening tests are pending.", style = MaterialTheme.typography.bodySmall)
    }
    if (lab.busy) {
        if (lab.progress != null) LinearProgressIndicator(progress = { lab.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = lab::cancel) { Text("Stop voice output operation") }
    }
    lab.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("Local output receives phrases as Hermes replies. Qwen streams PCM; MOSS decodes growing audio prefixes during generation, retaining a tail for context. MOSS latency and audio continuity still need phone testing. Phone CPU speed determines latency; short activity announcements also need synthesis. Interruption cancels generation and clears queued audio. No server plugin update is needed.", style = MaterialTheme.typography.bodySmall)
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
