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

/** Older MOSS helpers use implicit 48 kHz; current helpers declare their PCM contract. */
internal class ServerPcmInput(input: InputStream, val sampleRate: Int, private val finished: () -> Unit = {}) : FilterInputStream(input) {
    override fun close() { try { super.close() } finally { finished() } }
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
                error(speechHttpError("speech", status))
            }
            val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (type !in setOf("application/octet-stream", "audio/pcm")) {
                response.close()
                error("Server speech did not return streaming PCM. Check the Bellpull speech helper. The written answer is available.")
            }
            val declared = response.header("X-Audio-Sample-Rate") ?: response.body.contentType()?.parameter("rate")
            val sampleRate = declared?.toIntOrNull() ?: if (declared == null && type == "application/octet-stream") 48000 else 0
            if (sampleRate !in setOf(24000, 48000) || response.header("X-Audio-Channels")?.let { it != "1" } == true ||
                response.header("X-Audio-Format")?.let { it != "pcm_s16le" } == true) {
                response.close()
                error("Server speech returned an unsupported PCM format. Update the Bellpull speech helper.")
            }
            return ServerPcmInput(response.body.byteStream(), sampleRate) { try { response.close() } finally { forget(call) } }
        } catch (error: Exception) { forget(call); throw error }
    }
    override fun stt(wav: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "speech.wav", wav.toRequestBody("audio/wav".toMediaType())).build()
        val call = call("stt", body, 65)
        try {
            return call.execute().use { response ->
                check(response.isSuccessful) { speechHttpError("transcription", response.code) }
                val source = response.body.source()
                check(!source.request(1024 * 1024 + 1L)) { "Server transcription response is too large." }
                val value = Json.parseToJsonElement(source.readUtf8()).jsonObject["text"]
                check(value is JsonPrimitive && value.isString) { "Server transcription returned invalid text." }
                // Silence is a successful empty transcript, not a broken server or a
                // reason to launch a second recognizer. VoiceSession handles it.
                value.content.trim()
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

private fun speechHttpError(operation: String, status: Int): String {
    val action = when (status) {
        401, 403 -> "Check this server profile's connection and API key."
        404 -> "Update the Bellpull plugin on this server to enable speech routes."
        502, 503, 504 -> "Check the speech helper on your Bellpull server, then retry."
        else -> "Check the Bellpull speech helper, then retry."
    }
    return "Server $operation failed (HTTP $status). $action"
}

internal data class ServerSpeechTiming(val firstPcmMs: Long?, val totalMs: Long, val audioSeconds: Double)

/** Preserve half-samples across arbitrary HTTP chunk boundaries; never buffer a phrase. */
internal fun decodeServerPcm(input: InputStream, sink: LocalTtsSink, sampleRate: Int = 48000, firstPcm: () -> Unit = {}): Long {
    require(sampleRate == 24000 || sampleRate == 48000)
    val bytes = ByteArray(4800)
    var low = -1
    var frames = 0L
    var previous: Float? = null
    fun emit(samples: FloatArray) {
        if (samples.isEmpty()) return
        if (sink.isCancelled()) throw CancellationException("Server speech cancelled.")
        if (frames == 0L) firstPcm()
        if (!sink.onChunk(samples)) throw CancellationException("Server speech cancelled.")
        frames += samples.size
    }
    while (!sink.isCancelled()) {
        val count = input.read(bytes)
        if (count < 0) {
            check(low == -1) { "Server speech ended with a partial PCM sample." }
            if (sampleRate == 24000) previous?.let { emit(floatArrayOf(it, it)) }
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
        if (sampleRate == 48000) emit(samples)
        else {
            // Keep the existing 48 kHz player/queue contract, explicitly upsampling
            // Kokoro's 24 kHz across chunk boundaries instead of doubling its pitch.
            val expanded = FloatArray(samples.size * 2)
            var size = 0
            for (sample in samples) {
                previous?.let { expanded[size++] = it; expanded[size++] = (it + sample) * .5f }
                previous = sample
            }
            emit(expanded.copyOf(size))
        }
    }
    throw CancellationException("Server speech cancelled.")
}

internal class ServerMossEngine(private val transport: ServerSpeechTransport,
    private val telemetry: (ServerSpeechTiming) -> Unit = {
        android.util.Log.i("BellpullVoiceSpeech", "engine=server first_pcm_ms=${it.firstPcmMs} total_ms=${it.totalMs} audio_seconds=${it.audioSeconds}")
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
                decodeServerPcm(input, measuredSink, (input as? ServerPcmInput)?.sampleRate ?: 48000) { first = (System.nanoTime() - start) / 1_000_000 }
            }
            return 0
        } finally { telemetry(ServerSpeechTiming(first, (System.nanoTime() - start) / 1_000_000, frames / 48000.0)) }
    }
    override fun close() = transport.close()
}

/** Reuse the bounded local PCM queue, callbacks and playback cancellation. */
internal class ServerMossVoiceOutput(connection: ServerConnection, config: VoiceConfiguration,
    private val transport: ServerSpeechTransport = ProfileSpeechTransport(connection),
    usage: Int = android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION) : VoiceOutput {
    private val voice = LocalVoiceOutput(LocalTtsRuntime({}) { ServerMossEngine(transport) }, config.output, config.rate,
        severe = { false }, player = { rate, volume -> voicePcmPlayer(VoiceOutputEngine.SERVER_MOSS, rate, volume, usage) },
        cue = ::readyChime, cancelSynthesis = transport::cancel)
    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = voice.prepare(onReady, onError)
    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) = voice.speak(text, started, complete)
    override fun prefetch(text: String) = voice.prefetch(text)
    override fun retryPreparation() = voice.retryPreparation()
    override fun chime() = voice.chime()
    override fun stop() = voice.stop()
    override fun close() { voice.close(); transport.close() }
}

/** Server failures stay visible unless local fallback is separately enabled. */
internal class ServerVoiceTranscriber(private val transport: ServerSpeechTransport,
    private val allowLocalFallback: Boolean = false,
    private val local: () -> VoiceTranscriber?) : VoiceTranscriber {
    @Volatile private var closed = false
    private var fallback: VoiceTranscriber? = null
    override fun transcribe(wav: ByteArray): Result<String> = runCatching {
        checkOpen()
        try { transport.stt(wav).also { checkOpen() } }
        catch (error: Exception) {
            checkOpen()
            if (error is CancellationException) throw error
            if (!allowLocalFallback) throw error
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
