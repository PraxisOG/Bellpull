package org.praxisog.hermes

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString.Companion.toByteString

internal interface StreamingVoiceTranscriber : VoiceTranscriber {
    fun begin(partial: (String) -> Unit)
    fun audio(pcm: ByteArray)
}

private class StreamingUnavailable : IOException("This server supports batch transcription only.")

/** One bounded utterance per WebSocket. Partials are display-only.
 * Only an unsupported upgrade permits automatic batch fallback; a failed active
 * stream preserves the WAV for an explicit retry through VoiceSession, or uses
 * the user's explicitly enabled local fallback.
 */
internal class StreamingServerTranscriber(
    private val connection: ServerConnection,
    private val batch: VoiceTranscriber,
    private val local: (() -> VoiceTranscriber?)? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build(),
) : StreamingVoiceTranscriber {
    private val result = CompletableFuture<String>()
    private val closed = AtomicBoolean()
    private val finishing = AtomicBoolean()
    private var socket: WebSocket? = null
    private var began = false
    private var bytes = 0
    private var localFallback: VoiceTranscriber? = null

    override fun begin(partial: (String) -> Unit) {
        check(!began && !closed.get())
        began = true
        val request = Request.Builder().url(connection.profile.url.trimEnd('/') + "/api/bellpull/speech/stt/stream")
            .header("Authorization", "Bearer ${connection.key}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            private var ready = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get() || result.isDone) return
                try {
                    check(text.length <= 65536) { "Speech response too large." }
                    val value = Json.parseToJsonElement(text).jsonObject
                    when (value["type"]?.jsonPrimitive?.content) {
                        "ready" -> {
                            check(!ready)
                            ready = true
                        }
                        "partial", "final" -> {
                            check(ready)
                            val transcript = value["text"] as? JsonPrimitive
                            check(transcript?.isString == true)
                            if (value["type"]?.jsonPrimitive?.content == "final") {
                                check(finishing.get()) { "Speech finalized before recording ended." }
                                result.complete(transcript.content.trim())
                                webSocket.close(1000, "Finished")
                            } else partial(transcript.content)
                        }
                        "error" -> fail(IOException("Server transcription stream failed. Retry the recording."))
                        else -> error("Invalid speech stream response.")
                    }
                } catch (_: Exception) { fail(IOException("Invalid speech stream response. Retry the recording.")) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val failure = if (response?.code in setOf(404, 405, 501)) StreamingUnavailable()
                    else IOException("Server transcription connection failed. Retry the recording.", t)
                response?.close()
                result.completeExceptionally(failure)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                result.completeExceptionally(IOException("Speech stream ended before its final transcript. Retry the recording."))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                result.completeExceptionally(IOException("Speech stream ended before its final transcript. Retry the recording."))
            }
        })
        if (closed.get()) socket?.cancel()
        else socket?.send("""{"type":"start","sample_rate":16000,"channels":1,"format":"pcm_s16le"}""")
    }

    override fun audio(pcm: ByteArray) {
        if (closed.get() || result.isDone) return
        bytes += pcm.size
        if (pcm.size > 65536 || pcm.size % 2 != 0 || bytes > 16000 * 2 * 60 ||
            (socket?.queueSize() ?: 0) > 256 * 1024 ||
            socket?.send(pcm.toByteString()) != true)
            fail(IOException("Speech upload could not keep up. Retry the recording."))
    }

    override fun transcribe(wav: ByteArray): Result<String> = runCatching {
        if (closed.get()) throw CancellationException("Transcription cancelled.")
        if (!began) return@runCatching batch.transcribe(wav).getOrThrow()
        finishing.set(true)
        if (!result.isDone && socket?.send("""{"type":"finish"}""") != true)
            fail(IOException("Speech connection ended. Retry the recording."))
        try {
            result.get(35, TimeUnit.SECONDS)
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            if (closed.get() || cause is CancellationException) throw CancellationException("Transcription cancelled.")
            if (cause is StreamingUnavailable) batch.transcribe(wav).getOrThrow()
            else {
                val fallback = synchronized(this) {
                    if (closed.get()) throw CancellationException("Transcription cancelled.")
                    local?.invoke()?.also { localFallback = it }
                } ?: throw cause
                fallback.transcribe(wav).getOrThrow().also {
                    if (closed.get()) throw CancellationException("Transcription cancelled.")
                }
            }
        }
    }

    private fun fail(error: Throwable) {
        result.completeExceptionally(error)
        socket?.cancel()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        result.completeExceptionally(CancellationException("Transcription cancelled."))
        socket?.cancel()
        synchronized(this) { localFallback?.close() }
        batch.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
