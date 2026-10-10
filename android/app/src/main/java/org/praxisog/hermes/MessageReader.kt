package org.praxisog.hermes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Uses the user's installed speech engine; never installs an engine or selects a cloud service. */
internal class MessageReader(private val context: Context) {
    var readingMessageId: Int? by mutableStateOf(null)
        private set
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var closed = false
    private var generation = 0L
    private var pending: String? = null

    fun read(id: Int, text: String) {
        if (readingMessageId == id) { stop(); return }
        stop()
        if (text.isBlank() || closed) return
        readingMessageId = id
        pending = text
        if (engine == null) {
            engine = TextToSpeech(context.applicationContext) { status ->
                // Initialization can arrive before the constructor assignment completes.
                main.post {
                    if (!closed) {
                        if (status == TextToSpeech.SUCCESS) {
                            initialized = true
                            speakPending()
                        } else fail("Read aloud is unavailable. Check your installed speech engine.")
                    }
                }
            }
        } else if (initialized) speakPending()
    }

    private fun speakPending() {
        val tts = engine ?: return
        val text = pending ?: return
        pending = null
        val language = tts.setLanguage(Locale.getDefault())
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail("Your speech engine has no voice for this language.")
            return
        }
        val token = generation
        val parts = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        val last = "$token:${parts.lastIndex}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == last) main.post { if (token == generation) readingMessageId = null }
            }
            @Deprecated("Required by the platform listener")
            override fun onError(utteranceId: String?) {
                main.post { if (token == generation) fail("The speech engine could not read this message.") }
            }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
        parts.forEachIndexed { index, part ->
            val result = tts.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "$token:$index")
            if (result == TextToSpeech.ERROR) {
                fail("The speech engine could not read this message.")
                return
            }
        }
    }

    fun stop() {
        generation++
        pending = null
        readingMessageId = null
        engine?.stop()
    }

    private fun fail(message: String) {
        stop()
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        if (!initialized) { engine?.shutdown(); engine = null }
    }

    fun close() {
        closed = true
        stop()
        engine?.shutdown()
        engine = null
    }
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
