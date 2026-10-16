package org.praxisog.hermes

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileSpeechTransportTest {
    @Test fun realHttpCarriesProfileAuthAndUnchanged16KhzMicrophoneWav() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val audio = wav(ByteArray(16000) { it.toByte() })
        var path: String? = null
        var auth: String? = null
        var contentType: String? = null
        var received = byteArrayOf()
        server.createContext("/") { exchange ->
            path = exchange.requestURI.path
            auth = exchange.requestHeaders.getFirst("Authorization")
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            received = exchange.requestBody.readBytes()
            val result = "{\"text\":\" Heard the microphone. \"}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, result.size.toLong())
            exchange.responseBody.use { it.write(result) }
        }
        server.start()
        try {
            ProfileSpeechTransport(ServerConnection(ServerProfile("fixture", "Fixture",
                "http://127.0.0.1:${server.address.port}/p/fixture/"), "synthetic-profile-key")).use {
                assertEquals("Heard the microphone.", it.stt(audio))
            }
            assertEquals("/p/fixture/api/bellpull/speech/stt", path)
            assertEquals("Bearer synthetic-profile-key", auth)
            assertTrue(contentType!!.startsWith("multipart/form-data; boundary="))
            val body = received.toString(Charsets.ISO_8859_1)
            assertTrue(body.contains("name=\"audio\"; filename=\"speech.wav\""))
            val start = body.indexOf("RIFF")
            assertTrue(start >= 0)
            assertArrayEquals(audio, received.copyOfRange(start, start + audio.size))
            assertEquals(16000, ByteBuffer.wrap(received, start, audio.size).slice().order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        } finally { server.stop(0) }
    }

    @Test fun emptyRecognitionIsSilenceAndWrongTtsFormatNeverReachesPlayback() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 200
        var payload = "{\"text\":\"\"}"
        var type = "application/json"
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Content-Type", type)
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            ProfileSpeechTransport(ServerConnection(ServerProfile("fixture", "Fixture",
                "http://127.0.0.1:${server.address.port}"), "synthetic-key")).use { transport ->
                assertEquals("", transport.stt(wav(ByteArray(3200))))
                type = "audio/wav"; payload = "RIFF not raw PCM"
                assertThrows(IllegalStateException::class.java) { transport.tts("Hello", { false }) }
                status = 404
                val missing = assertThrows(IllegalStateException::class.java) { transport.stt(wav(ByteArray(3200))) }
                assertTrue(missing.message!!.contains("Update the Bellpull plugin"))
                status = 503
                val unavailable = assertThrows(IllegalStateException::class.java) { transport.stt(wav(ByteArray(3200))) }
                assertTrue(unavailable.message!!.contains("speech helper"))
            }
        } finally { server.stop(0) }
    }
}
