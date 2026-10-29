package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Foreground-only, bounded utterance capture. Closing the panel cancels late results. */
internal class AssistantSpeech(
    private val scope: CoroutineScope,
    private val capture: VoiceCapture,
    private val transcriber: () -> VoiceTranscriber,
    private val onText: (String) -> Unit,
) {
    var listening by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    private val gate = VoiceGate()
    private var deadline: Job? = null
    private var task: Job? = null
    private var provider: VoiceTranscriber? = null
    private var generation = 0

    fun start() {
        if (listening || busy) return
        problem = null; gate.reset()
        provider = try { transcriber() } catch (e: Exception) { problem = e.message ?: "Speech recognition is unavailable."; return }
        listening = true
        if (!capture.start({ pcm, count ->
            gate.accept(pcm, count).complete?.let(::recognize)
        }, { message -> cancel(); problem = message })) {
            cancel(); problem = "Microphone unavailable. Check permission and other recording apps."
            return
        }
        deadline = scope.launch { delay(15_000); finish() }
    }
    fun finish() {
        if (!listening) return
        val audio = gate.finish()
        if (audio == null) { cancel(); problem = "No speech heard. Try again or type your request." }
        else recognize(audio)
    }
    private fun recognize(audio: ByteArray) {
        if (!listening) return
        capture.close(); listening = false; busy = true; deadline?.cancel(); deadline = null
        val current = provider ?: run { cancel(); return }
        val token = generation
        task = scope.launch {
            try {
                val text = withTimeout(60_000) { withContext(Dispatchers.IO) { current.transcribe(audio).getOrThrow() } }.trim()
                ensureActive()
                if (token == generation) {
                    if (text.isBlank()) problem = "No speech recognized. Try again or type your request."
                    else onText(text)
                }
            } catch (e: TimeoutCancellationException) {
                if (token == generation) problem = "Speech recognition timed out. Try again or type your request."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) problem = e.message ?: "Speech recognition failed." }
            finally {
                current.close()
                if (token == generation) { provider = null; busy = false }
            }
        }
    }
    fun cancel() {
        generation++; capture.close(); gate.reset(); deadline?.cancel(); deadline = null
        task?.cancel(); task = null; provider?.close(); provider = null
        listening = false; busy = false
    }
}
