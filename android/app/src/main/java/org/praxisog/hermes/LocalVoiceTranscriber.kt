package org.praxisog.hermes

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Cancellation never frees a native model on the UI thread or during inference. */
internal class LocalVoiceTranscriber(private val runtime: SpeechModelRuntime, private val model: SpeechModel,
    private val severe: () -> Boolean = { false }) : VoiceTranscriber {
    private val closed = AtomicBoolean(false)
    override fun transcribe(wav: ByteArray): Result<String> = runCatching {
        checkOpen()
        check(!severe()) { "Phone reached severe thermal status. Let it cool before using local speech." }
        val audio = SpeechAudio.readWav(wav)
        runtime.withModel(model) { engine, _ ->
            checkOpen()
            check(!severe()) { "Phone reached severe thermal status. Let it cool before using local speech." }
            val text = engine.transcribe(audio)
            checkOpen()
            text
        }
    }
    private fun checkOpen() { if (closed.get()) throw CancellationException("Local transcription cancelled.") }
    override fun close() { closed.set(true) }
}
