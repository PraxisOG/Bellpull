package org.praxisog.hermes

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Stable pointer handler: recomposing the active pulse must not cancel a held gesture. */
@Composable
internal fun VoiceButton(active: Boolean, enabled: Boolean, reduceMotion: Boolean,
    onTap: () -> Unit, onHold: () -> Unit, onRelease: (Boolean) -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    val available by rememberUpdatedState(enabled)
    val pulse = if (active && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "Voice active")
        transition.animateFloat(.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "Microphone pulse").value
    } else 1f
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    Box(Modifier.size(48.dp).graphicsLayer { alpha = pulse }.background(color, CircleShape)
        .focusable(enabled).semantics {
            role = Role.Button
            contentDescription = if (active) "End voice conversation" else "Voice conversation"
            stateDescription = if (active) "Voice on" else "Voice off"
            if (!enabled) disabled()
            onClick { if (available) tap(); available }
            onLongClick("Hold to dictate") { if (available) hold(); available }
        }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown().consume()
                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    up = waitForUpOrCancellation()
                    true
                } ?: false
                if (ended) {
                    up?.let { it.consume(); if (available) tap() }
                } else if (available) {
                    // detectTapGestures treats cancellation after a long press as release.
                    // Dictation must discard that gesture, including when this node disappears.
                    var canceled = true
                    hold()
                    try {
                        up = waitForUpOrCancellation()
                        canceled = up == null
                        up?.consume()
                    } finally { release(canceled) }
                }
            }
        }, contentAlignment = Alignment.Center) { VoiceGlyph(active) }
}

@Composable
internal fun VoiceInlineStatus(voice: VoiceSession?, error: String?, onSettings: () -> Unit,
    onReview: () -> Unit) {
    if (voice == null && error == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(voice?.let { "Voice on · ${it.state.label}" } ?: "Voice unavailable", style = MaterialTheme.typography.labelMedium)
        if (voice?.microphoneMuted == true) Text("Microphone muted", style = MaterialTheme.typography.bodySmall)
        if (!voice?.partialTranscript.isNullOrBlank()) Text(voice!!.partialTranscript, style = MaterialTheme.typography.bodyMedium)
        (voice?.notice ?: error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (voice == null) TextButton(onClick = onSettings) { Text("Voice settings") }
            else {
                if (voice.canRetryTranscription) TextButton(onClick = voice::retryTranscription) { Text("Retry transcription") }
                else if (voice.state == VoiceState.PAUSED) TextButton(onClick = voice::resume) { Text("Resume voice") }
                if (voice.state == VoiceState.REVIEW) TextButton(onClick = onReview) { Text("Put words in draft") }
                TextButton(onClick = voice::close) { Text("End voice") }
            }
        }
    }
}

/** Insert at the current cursor/selection without discarding an existing typed draft. */
internal fun insertVoiceDictation(draft: TextFieldValue, words: String): TextFieldValue {
    val start = draft.selection.min.coerceIn(0, draft.text.length)
    val end = draft.selection.max.coerceIn(start, draft.text.length)
    val prefix = draft.text.take(start)
    val suffix = draft.text.drop(end)
    val insertion = (if (prefix.isNotEmpty() && !prefix.last().isWhitespace()) " " else "") + words.trim() +
        (if (suffix.isNotEmpty() && !suffix.first().isWhitespace()) " " else "")
    return TextFieldValue(prefix + insertion + suffix, TextRange(prefix.length + insertion.length))
}
