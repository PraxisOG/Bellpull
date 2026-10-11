package org.praxisog.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun VoiceGlyph() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(20.dp)) {
        drawRoundRect(color, Offset(size.width * .35f, 0f), androidx.compose.ui.geometry.Size(size.width * .3f, size.height * .6f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        drawArc(color, 0f, 180f, false, Offset(size.width * .15f, size.height * .15f), androidx.compose.ui.geometry.Size(size.width * .7f, size.height * .65f), style = Stroke(1.7.dp.toPx()))
        drawLine(color, Offset(size.width * .5f, size.height * .8f), Offset(size.width * .5f, size.height), 1.7.dp.toPx())
    }
}
@Composable
internal fun VoicePanel(voice: VoiceSession?, config: VoiceConfiguration, startError: String?,
    onSave: (VoiceConfiguration) -> Unit, onStart: (VoiceMode) -> Unit, onDismiss: () -> Unit,
    onReturnToChat: () -> Unit = onDismiss) {
    var editing by remember { mutableStateOf(config.endpoint.isBlank()) }
    var mode by remember { mutableStateOf(VoiceMode.CONVERSATION) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.imePadding().padding(16.dp).widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Voice conversation", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editing) VoiceSettingsEditor(config, voice == null, { onSave(it); editing = false })
                    else if (voice == null) {
                        Text("Speak with Hermes in this conversation. Audio goes only to your configured transcription service; answers use an installed offline voice.")
                        Text("Transcription model: ${config.model}", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(mode == VoiceMode.CONVERSATION, { mode = VoiceMode.CONVERSATION }, label = { Text("Hands-free") })
                            FilterChip(mode == VoiceMode.DICTATION, { mode = VoiceMode.DICTATION }, label = { Text("Review before sending") })
                            FilterChip(mode == VoiceMode.PUSH_TO_TALK, { mode = VoiceMode.PUSH_TO_TALK }, label = { Text("Push to talk") })
                        }
                        Text("Hands-free sends recognized speech automatically. Review mode lets you correct each transcription. A visible notification controls the microphone while the app is in the background.", style = MaterialTheme.typography.bodySmall)
                        startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { onStart(mode) }) { Text("Start voice") }
                        TextButton(onClick = { editing = true }) { Text("Voice settings") }
                    } else {
                        Text(voice.state.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { contentDescription = "Voice state: ${voice.state.label}" })
                        if (voice.state in setOf(VoiceState.STARTING, VoiceState.TRANSCRIBING, VoiceState.WORKING, VoiceState.WAITING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${when (voice.mode) { VoiceMode.CONVERSATION -> "Hands-free"; VoiceMode.DICTATION -> "Review before sending"; VoiceMode.PUSH_TO_TALK -> "Push to talk" }} · ${voice.voiceName}", style = MaterialTheme.typography.labelMedium)
                        voice.notice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(voice.mode == VoiceMode.CONVERSATION, { voice.chooseMode(VoiceMode.CONVERSATION) }, label = { Text("Hands-free") })
                            FilterChip(voice.mode == VoiceMode.DICTATION, { voice.chooseMode(VoiceMode.DICTATION) }, label = { Text("Review first") })
                            FilterChip(voice.mode == VoiceMode.PUSH_TO_TALK, { voice.chooseMode(VoiceMode.PUSH_TO_TALK) }, label = { Text("Push to talk") })
                        }
                        if (voice.state == VoiceState.REVIEW) {
                            OutlinedTextField(voice.transcript, voice::correct, label = { Text("Your words") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                            Button(onClick = voice::sendTranscript, enabled = voice.transcript.isNotBlank()) { Text("Send to Hermes") }
                        } else if (voice.transcript.isNotBlank()) { Text("You said", style = MaterialTheme.typography.labelLarge); Text(voice.transcript) }
                        if (voice.mode == VoiceMode.PUSH_TO_TALK && voice.state !in setOf(VoiceState.PAUSED, VoiceState.TRANSCRIBING, VoiceState.REVIEW)) {
                            val held = voice.holdTurn
                            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                                .pointerInput(voice) { detectTapGestures(onPress = {
                                    voice.beginPushToTalk()
                                    val released = tryAwaitRelease()
                                    voice.endPushToTalk(canceled = !released)
                                }) }.focusable().semantics {
                                    role = Role.Button; contentDescription = "Hold to speak"
                                    onClick { if (held) voice.endPushToTalk() else voice.beginPushToTalk(); true }
                                }, contentAlignment = Alignment.Center) {
                                Text(if (held) "Release to send" else "Hold to speak")
                            }
                        }
                        if (voice.queuedText.isNotBlank()) Text("Next turn waits for the current work to settle. You can cancel the current work below.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (voice.state == VoiceState.READY && voice.mode != VoiceMode.PUSH_TO_TALK) Button(onClick = voice::resume) { Text("Record next turn") }
                            else if (voice.state == VoiceState.PAUSED) Button(onClick = voice::resume) { Text("Resume voice") }
                            else OutlinedButton(onClick = { voice.pause() }) { Text("Pause microphone") }
                            if (voice.canRetryTranscription) TextButton(onClick = voice::retryTranscription) { Text("Retry transcription") }
                            OutlinedButton(onClick = voice::stopSpeaking, enabled = voice.state == VoiceState.SPEAKING) { Text("Stop speaking") }
                            TextButton(onClick = voice::cancelWork) { Text("Cancel agent work") }
                        }
                        if (voice.mode != VoiceMode.PUSH_TO_TALK && voice.state in setOf(VoiceState.LISTENING, VoiceState.RECORDING)) {
                            FilterChip(voice.holdTurn, { voice.hold(!voice.holdTurn) }, label = { Text("Hold my turn") })
                            TextButton(onClick = voice::finishRecording) { Text("Finish speaking") }
                        }
                        Text("Stop speaking clears playback. Cancel agent work requests a stop; it cannot undo tools that already ran.", style = MaterialTheme.typography.bodySmall)
                        voice.transcriptionMs?.let { duration ->
                            Text("From end of capture · transcription ${duration}ms" +
                                (voice.firstTextMs?.let { " · first text ${it}ms" } ?: "") +
                                (voice.firstPlaybackMs?.let { " · speech started ${it}ms" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        if (voice.playedText.isNotBlank()) { Text("Playback completed", style = MaterialTheme.typography.labelLarge); Text(voice.playedText, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (voice != null) TextButton(onClick = voice::close) { Text("End voice") }
                    TextButton(onClick = if (voice == null) onDismiss else onReturnToChat) { Text(if (voice == null) "Close" else "Return to chat") }
                }
            }
        }
    }
}
@Composable
private fun VoiceSettingsEditor(initial: VoiceConfiguration, enabled: Boolean, onSave: (VoiceConfiguration) -> Unit) {
    var endpoint by remember { mutableStateOf(initial.endpoint) }; var key by remember { mutableStateOf(initial.key) }
    var model by remember { mutableStateOf(initial.model) }; var rate by remember { mutableFloatStateOf(initial.rate) }
    var pause by remember { mutableIntStateOf(initial.pauseMs) }; var concise by remember { mutableStateOf(initial.concise) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Private transcription", style = MaterialTheme.typography.titleMedium)
    Text("Use a service you manage that accepts an OpenAI-compatible WAV transcription request. This is separate from Hermes; no service is selected automatically.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Full transcription URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("For example: https://your-speech-host/v1/audio/transcriptions", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(key, { key = it }, label = { Text("Speech service key (if required)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model, { model = it }, label = { Text("Transcription model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Speech rate: ${"%.2f".format(rate)}×"); Slider(rate, { rate = it }, valueRange = .7f..1.5f)
    Text("End-of-turn pause: ${"%.1f".format(pause / 1000f)} seconds"); Slider(pause.toFloat(), { pause = it.toInt() }, valueRange = 1000f..3500f)
    Row { Checkbox(concise, { concise = it }); Text("Concise spoken-answer preset") }
    Text("The preset is included visibly in voice messages. Typed messages and their model settings keep their existing behavior.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = enabled, onClick = { runCatching { onSave(VoiceConfiguration(endpoint, key, model, rate, pause, concise)) }.onFailure { error = it.message ?: "Could not save voice settings." } }) { Text("Save voice settings") }
}
