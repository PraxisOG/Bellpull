package org.praxisog.hermes

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Uses the same explicit output selection as conversation mode. */
internal class MessageReader(private val context: Context,
    private val create: (Int) -> VoiceOutput = { AndroidVoiceOutput(context, 1.05f, it) }) {
    var readingMessageId: Int? by mutableStateOf(null)
        private set
    private var output: VoiceOutput? = null
    private var closed = false
    private var generation = 0L
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    fun read(id: Int, text: String) {
        if (readingMessageId == id) { stop(); return }
        stop()
        if (closed || text.isBlank()) return
        readingMessageId = id
        val token = generation
        try {
            val voice = create(AudioAttributes.USAGE_MEDIA); output = voice
            // Bound each inference phrase while preserving the full written answer.
            val parts = speechChunks(text, 240)
            fun speak(index: Int) {
                if (token != generation || closed) return
                if (index >= parts.size) { stop(); return }
                voice.speak(parts[index], {}, { if (token == generation) speak(index + 1) })
                parts.getOrNull(index + 1)?.let(voice::prefetch)
            }
            voice.prepare({
                if (token == generation && !closed) {
                    // Standalone reading follows Android's selected media route, including
                    // car audio. It neither selects the phone speaker nor opens call mode.
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener({ change ->
                            if (token == generation && change != AudioManager.AUDIOFOCUS_GAIN)
                                fail("Read aloud was interrupted. Tap Read aloud again when ready.")
                        }, Handler(Looper.getMainLooper())).build()
                    focus = request
                    val granted = runCatching { audio.requestAudioFocus(request) }
                        .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                    if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) speak(0)
                    else fail("Audio is unavailable. Try Read aloud again when ready.")
                }
            }, { if (token == generation) fail(it) })
        } catch (error: Exception) { fail(error.message ?: "Read aloud is unavailable.") }
    }
    fun stop() {
        generation++; readingMessageId = null
        val previous = output; output = null
        try { previous?.close() } finally {
            val request = focus; focus = null
            request?.let(audio::abandonAudioFocusRequest)
        }
    }
    private fun fail(message: String) { stop(); Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    fun close() { closed = true; stop() }
}

/** Preserve every character and avoid splitting a surrogate pair across engine requests. */
internal fun speechChunks(text: String, limit: Int): List<String> {
    require(limit >= 2)
    val parts = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length) {
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val pause = (end - 1 downTo start + (end - start) / 2).firstOrNull { text[it].isWhitespace() }
            if (pause != null) end = pause + 1
        }
        parts += text.substring(start, end)
        start = end
    }
    return parts
}
