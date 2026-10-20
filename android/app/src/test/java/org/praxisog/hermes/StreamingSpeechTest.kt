package org.praxisog.hermes

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class StreamingSpeechTest {
    private class Batch : VoiceTranscriber {
        var calls = 0
        override fun transcribe(wav: ByteArray): Result<String> { calls++; return Result.success("batch words") }
        override fun close() {}
    }
    private fun connection(server: MockWebServer) = ServerConnection(
        ServerProfile("fixture", "Fixture", server.url("/p/fixture/").toString()), "fixture-key")

    @Test fun actualWebsocketSendsPcmAndShowsPartialBeforeOneFinal() {
        MockWebServer().use { server ->
            val partial = CountDownLatch(1)
            val bytes = byteArrayOf(0, 1, 2, 3)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"ready","sample_rate":16000}""")
                }
                override fun onMessage(webSocket: WebSocket, data: ByteString) {
                    assertArrayEquals(bytes, data.toByteArray())
                    webSocket.send("""{"type":"partial","text":"heard"}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("finish")) webSocket.send("""{"type":"final","text":"heard everything"}""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            val batch = Batch()
            val live = StreamingServerTranscriber(connection(server), batch)
            try {
                live.begin { assertEquals("heard", it); partial.countDown() }
                live.audio(bytes)
                assertTrue(partial.await(5, TimeUnit.SECONDS))
                assertEquals(0, batch.calls)
                assertEquals("heard everything", live.transcribe(wav(bytes)).getOrThrow())
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/p/fixture/api/bellpull/speech/stt/stream", request.path)
                assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            } finally { live.close() }
        }
    }

    @Test fun onlyUnsupportedUpgradeAllowsBatchFallback() {
        for (status in listOf(404, 401, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status))
            val batch = Batch()
            val live = StreamingServerTranscriber(connection(server), batch)
            try {
                live.begin {}
                live.audio(byteArrayOf(0, 0))
                val result = live.transcribe(wav(byteArrayOf(0, 0)))
                assertEquals(status == 404, result.isSuccess)
                assertEquals(if (status == 404) 1 else 0, batch.calls)
            } finally { live.close() }
        }
    }

    @Test fun cancelledStreamNeverReplaysThroughBatch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val batch = Batch()
            val live = StreamingServerTranscriber(connection(server), batch)
            live.begin {}
            live.close()
            assertTrue(live.transcribe(wav(byteArrayOf(0, 0))).isFailure)
            assertEquals(0, batch.calls)
        }
    }

    @Test fun serverHangupWithoutFinalFailsPromptlyWithoutReplay() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"ready","sample_rate":16000}""")
                    webSocket.close(1001, "Stopping")
                }
            }))
            val batch = Batch()
            val live = StreamingServerTranscriber(connection(server), batch)
            try {
                live.begin {}
                val started = System.nanoTime()
                assertTrue(live.transcribe(wav(byteArrayOf(0, 0))).isFailure)
                assertTrue("Hang-up waited for the 35-second inference timeout",
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5)
                assertEquals(0, batch.calls)
            } finally { live.close() }
        }
    }

    @Test fun configuredLocalFallbackReceivesOriginalWavWithoutServerReplay() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val batch = Batch()
            val audio = wav(byteArrayOf(1, 2, 3, 4))
            var localCalls = 0
            var localClosed = false
            val live = StreamingServerTranscriber(connection(server), batch, local = {
                object : VoiceTranscriber {
                    override fun transcribe(wav: ByteArray): Result<String> {
                        assertSame(audio, wav); localCalls++
                        return Result.success("local words")
                    }
                    override fun close() { localClosed = true }
                }
            })
            try {
                live.begin {}
                assertEquals("local words", live.transcribe(audio).getOrThrow())
                assertEquals(1, localCalls)
                assertEquals(0, batch.calls)
            } finally { live.close() }
            assertTrue(localClosed)
        }
    }
}
