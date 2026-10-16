package org.praxisog.hermes

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A deliberately small transport seam shared by both offload directions. */
internal interface ServerSpeechTransport : AutoCloseable {
    fun tts(text: String, cancelled: () -> Boolean): InputStream
    fun stt(wav: ByteArray): String
    fun cancel()
    override fun close() = cancel()
}

internal class ProfileSpeechTransport(private val connection: ServerConnection) : ServerSpeechTransport {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(125, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val calls = mutableSetOf<Call>()
    @Volatile private var closed = false
    private fun call(path: String, body: okhttp3.RequestBody, seconds: Long): Call {
        val request = Request.Builder().url(connection.profile.url.trimEnd('/') + "/api/bellpull/speech/" + path)
            .header("Authorization", "Bearer ${connection.key}").post(body).build()
        val call = client.newCall(request)
        call.timeout().timeout(seconds, TimeUnit.SECONDS)
        synchronized(calls) {
            check(!closed) { "Server speech was closed." }
            calls.add(call)
        }
        return call
    }
    private fun forget(call: Call) { synchronized(calls) { calls.remove(call) } }
    override fun tts(text: String, cancelled: () -> Boolean): InputStream {
        require(text.length in 1..4000) { "Server speech phrases must contain 1..4000 characters." }
        val body = buildJsonObject { put("text", text); put("stream", true) }.toString()
        val call = call("tts?stream=true", body.toRequestBody("application/json".toMediaType()), 125)
        try {
            if (cancelled()) { call.cancel(); throw CancellationException("Server speech cancelled.") }
            val response = call.execute()
            if (!response.isSuccessful) {
                val status = response.code; response.close()
                error("Server speech failed (HTTP $status). The written answer is available.")
            }
            return object : FilterInputStream(response.body.byteStream()) {
                override fun close() { try { response.close() } finally { forget(call) } }
            }
        } catch (error: Exception) { forget(call); throw error }
    }
    override fun stt(wav: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "speech.wav", wav.toRequestBody("audio/wav".toMediaType())).build()
        val call = call("stt", body, 65)
        try {
            return call.execute().use { response ->
                check(response.isSuccessful) { "Server transcription failed (HTTP ${response.code})." }
                val source = response.body.source()
                check(!source.request(1024 * 1024 + 1L)) { "Server transcription response is too large." }
                val value = Json.parseToJsonElement(source.readUtf8()).jsonObject["text"]
                check(value is JsonPrimitive && value.isString) { "Server transcription returned invalid text." }
                val text = value.content.trim()
                check(!text.isNullOrBlank()) { "Server transcription returned no text." }
                text
            }
        } finally { forget(call) }
    }
    override fun cancel() { synchronized(calls) { calls.forEach { it.cancel() } } }
    override fun close() {
        synchronized(calls) { closed = true; calls.forEach { it.cancel() } }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal data class ServerSpeechTiming(val firstPcmMs: Long?, val totalMs: Long, val audioSeconds: Double)

/** Preserve half-samples across arbitrary HTTP chunk boundaries; never buffer a phrase. */
internal fun decodeServerPcm(input: InputStream, sink: LocalTtsSink, firstPcm: () -> Unit = {}): Long {
    val bytes = ByteArray(4800)
    var low = -1
    var frames = 0L
    while (!sink.isCancelled()) {
        val count = input.read(bytes)
        if (count < 0) {
            check(low == -1) { "Server speech ended with a partial PCM sample." }
            check(frames > 0) { "Server speech returned no audio." }
            return frames
        }
        if (count == 0) continue
        val samples = FloatArray((count + if (low >= 0) 1 else 0) / 2)
        var at = 0
        for (i in 0 until count) {
            val byte = bytes[i].toInt() and 255
            if (low < 0) low = byte else {
                samples[at++] = ((byte shl 8) or low).toShort().toFloat() / 32768f
                low = -1
            }
        }
        if (samples.isNotEmpty()) {
            if (frames == 0L) firstPcm()
            if (!sink.onChunk(samples)) throw CancellationException("Server speech cancelled.")
            frames += samples.size
        }
    }
    throw CancellationException("Server speech cancelled.")
}

internal class ServerMossEngine(private val transport: ServerSpeechTransport,
    private val telemetry: (ServerSpeechTiming) -> Unit = {
        android.util.Log.i("BellpullVoiceSpeech", "engine=server-moss first_pcm_ms=${it.firstPcmMs} total_ms=${it.totalMs} audio_seconds=${it.audioSeconds}")
    }) : LocalTtsEngine {
    override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
        val start = System.nanoTime()
        var first: Long? = null
        var frames = 0L
        try {
            transport.tts(text, sink::isCancelled).use { input ->
                val measuredSink = object : LocalTtsSink by sink {
                    override fun onChunk(samples: FloatArray): Boolean {
                        frames += samples.size
                        return sink.onChunk(samples)
                    }
                }
                decodeServerPcm(input, measuredSink) { first = (System.nanoTime() - start) / 1_000_000 }
            }
            return 0
        } finally { telemetry(ServerSpeechTiming(first, (System.nanoTime() - start) / 1_000_000, frames / 48000.0)) }
    }
    override fun close() = transport.close()
}

/** Reuse the bounded local PCM queue, callbacks and playback cancellation. */
internal class ServerMossVoiceOutput(connection: ServerConnection, config: VoiceConfiguration,
    private val transport: ServerSpeechTransport = ProfileSpeechTransport(connection)) : VoiceOutput {
    private val voice = LocalVoiceOutput(LocalTtsRuntime({}) { ServerMossEngine(transport) }, config.output, config.rate,
        severe = { false }, player = { rate, volume -> AndroidVoicePcmPlayer(rate, volume, 48000) },
        cue = ::readyChime, cancelSynthesis = transport::cancel)
    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = voice.prepare(onReady, onError)
    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) = voice.speak(text, started, complete)
    override fun chime() = voice.chime()
    override fun stop() = voice.stop()
    override fun close() { voice.close(); transport.close() }
}

/** Only the explicit server-input setting permits local fallback; close fences both paths. */
internal class ServerVoiceTranscriber(private val transport: ServerSpeechTransport,
    private val local: () -> VoiceTranscriber?) : VoiceTranscriber {
    @Volatile private var closed = false
    private var fallback: VoiceTranscriber? = null
    override fun transcribe(wav: ByteArray): Result<String> = runCatching {
        checkOpen()
        try { transport.stt(wav).also { checkOpen() } }
        catch (error: Exception) {
            checkOpen()
            if (error is CancellationException) throw error
            val next = synchronized(this) {
                checkOpen()
                local()?.also { fallback = it }
            } ?: throw error
            next.transcribe(wav).getOrThrow().also { checkOpen() }
        }
    }
    private fun checkOpen() { if (closed) throw CancellationException("Server transcription cancelled.") }
    override fun close() {
        synchronized(this) { closed = true; fallback?.close() }
        transport.close()
    }
}
