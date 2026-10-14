package org.praxisog.hermes.core

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import java.net.URI
import java.util.concurrent.TimeUnit

/** An explicitly selected speech service. Never receives the Hermes bearer token. */
class PrivateTranscription(private val endpoint: String, private val key: String, private val model: String,
    private val http: OkHttpClient = sharedHttp) {
    @Volatile private var call: Call? = null
    @Volatile private var closed = false
    init { validateEndpoint(endpoint); require(model.isNotBlank() && model.length <= 200) }
    override fun toString() = "PrivateTranscription(credentials=redacted)"
    fun transcribe(wav: ByteArray): Result<String> = runCatching {
        check(!closed) { "Transcription was canceled." }
        require(wav.size in 45..1_920_044) { "Recording exceeds the 60-second limit." }
        check(key.all { it.code in 32..126 }) { "The speech service key contains invalid characters. Re-enter it in Voice settings." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model).addFormDataPart("response_format", "json")
            .addFormDataPart("file", "voice.wav", wav.toRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url(endpoint).post(body).apply {
            if (key.isNotBlank()) header("Authorization", "Bearer $key")
        }.build()
        val current = http.newCall(request)
        call = current
        if (closed) current.cancel()
        try {
            current.execute().use { response ->
                check(response.isSuccessful) { "Transcription service returned HTTP ${response.code}. Check its URL, key and model." }
                val source = response.body.source()
                source.request(65_537)
                val bytes = source.readByteArray(minOf(source.buffer.size, 65_537))
                check(bytes.size <= 65_536) { "Transcription response is too large." }
                val text = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["text"]
                require(text is JsonPrimitive && text.isString) { "Transcription service did not return JSON text." }
                text.content.trim()
            }
        } finally { if (call === current) call = null }
    }.recoverCatching { error ->
        if (error is java.io.IOException) throw IllegalStateException("Could not reach the transcription service. Your recording was not sent to Hermes.")
        if (error is IllegalStateException || error is IllegalArgumentException && error !is kotlinx.serialization.SerializationException) throw error
        throw IllegalStateException("The transcription service returned an invalid response.")
    }
    fun close() { closed = true; call?.cancel() }
    companion object {
        private val sharedHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        fun validateEndpoint(value: String): String {
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid transcription URL.") }
            val host = uri.host?.lowercase().orEmpty()
            require(uri.scheme in setOf("http", "https") && host.isNotBlank()) { "Enter a full HTTP or HTTPS transcription URL." }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) {
                "Use a URL without embedded credentials, query or fragment."
            }
            val path = uri.rawPath.orEmpty()
            require(path.startsWith('/') && path.length > 1 && !path.contains('%') && !path.contains('\\') &&
                path.split('/').none { it in setOf(".", "..") }) { "Enter the service's exact transcription path." }
            val labels = host.split('.')
            val octets = if (labels.size == 4 && labels.all { label ->
                label.length in 1..3 && label.all { it in '0'..'9' } &&
                    (label.length == 1 || label[0] != '0')
            }) labels.map { it.toInt() } else emptyList()
            val tailnet = host.endsWith(".ts.net") || (octets.size == 4 && octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127)
            require(uri.scheme == "https" || host in setOf("localhost", "127.0.0.1") || tailnet) {
                "Plain HTTP is allowed only over Tailscale or loopback."
            }
            return uri.toString()
        }
    }
}
