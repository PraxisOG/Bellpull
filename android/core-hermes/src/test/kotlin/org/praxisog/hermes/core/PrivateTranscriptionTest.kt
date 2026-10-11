package org.praxisog.hermes.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PrivateTranscriptionTest {
    private val wav = ByteArray(100) { 1 }
    @Test fun multipartUsesSeparateCredentialsExactPathAndSelectedModel() {
        var captured: Request? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"text\":\" Words recognized. \"}".toResponseBody()).build()
        }.build()
        val client = PrivateTranscription("https://speech.invalid/private/transcriptions", "speech-only", "local-whisper", http)
        assertEquals("Words recognized.", client.transcribe(wav).getOrThrow())
        val request = captured!!
        assertEquals("/private/transcriptions", request.url.encodedPath)
        assertEquals("Bearer speech-only", request.header("Authorization"))
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("local-whisper")); assertTrue(body.contains("filename=\"voice.wav\"")); assertTrue(body.contains("audio/wav"))
        assertFalse(client.toString().contains("speech-only"))
    }
    @Test fun unavailableServiceIsVisibleAndNeverRetriedOrSentToHermes() {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("provider secret must not escape".toResponseBody()).build()
        }.build()
        val result = PrivateTranscription("https://speech.invalid/v1/audio/transcriptions", "", "whisper", http).transcribe(wav)
        assertTrue(result.isFailure); assertEquals(1, calls.get()); assertFalse(result.exceptionOrNull()!!.message!!.contains("secret"))
        // OkHttp's invalid-header exception can include the header value. Never expose a key.
        for (key in listOf("private-secret\nbad", "private-secret\u0100")) {
            val invalid = PrivateTranscription("https://speech.invalid/v1/audio/transcriptions", key, "whisper", http).transcribe(wav)
            assertTrue(invalid.isFailure); assertEquals(1, calls.get())
            assertFalse(invalid.exceptionOrNull()!!.message.orEmpty().contains("private-secret"))
        }
    }
    @Test fun closedClientNeverUploads() {
        val client = PrivateTranscription("https://speech.invalid/v1/audio/transcriptions", "", "whisper")
        client.close(); assertTrue(client.transcribe(wav).isFailure)
    }
    @Test fun malformedAndOversizedResponseAreRejected() {
        for (body in listOf("{bad json private text", "x".repeat(70000), "{}",
            "{\"text\":null}", "{\"text\":123}", "{\"text\":{}}")) {
            val http = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build() }.build()
            val result = PrivateTranscription("https://speech.invalid/v1/audio/transcriptions", "", "whisper", http).transcribe(wav)
            assertTrue(result.isFailure); assertFalse(result.exceptionOrNull()!!.message.orEmpty().contains("private text"))
        }
    }
    @Test fun urlsDoNotAllowCredentialLeakageOrInsecureLan() {
        for (url in listOf("http://192.168.1.4/v1/audio/transcriptions", "https://user:key@host/transcriptions", "https://host/path?key=x",
            "https://host/path#frag", "https://host/a/../transcriptions", "https://host/%2ftranscriptions", "https://host")) {
            assertTrue(url, runCatching { PrivateTranscription.validateEndpoint(url) }.isFailure)
        }
        for (url in listOf("https://speech.invalid/v1/audio/transcriptions", "http://speech.tailabc.ts.net/transcriptions", "http://127.0.0.1:18647/transcriptions"))
            assertEquals(url, PrivateTranscription.validateEndpoint(url))
    }
}
