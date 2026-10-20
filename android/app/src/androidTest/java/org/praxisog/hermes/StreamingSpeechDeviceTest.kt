package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real PCM over the production Android WebSocket implementation and plugin proxy,
 * into the server's native CPU model. Public prerecorded fixture; no live chat.
 */
@RunWith(AndroidJUnit4::class)
class StreamingSpeechDeviceTest {
    @Test fun publicSpeechStreamsThroughPluginAndFinalizesWithoutBatchReplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "org.praxisog.hermes.fixture")
        val audio = File(context.filesDir, "streaming-clean.wav").readBytes()
        assertEquals("812d86d5b7c92e72489948254ff6177117206c44878ae942d1fdb01e12d9fe8c",
            MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) })
        val connection = ServerConnection(ServerProfile("fixture", "Fixture", "http://127.0.0.1:18648/p/fixture"),
            "phone-stream-fixture-key")
        val partials = AtomicInteger()
        val first = AtomicLong()
        val began = SystemClock.elapsedRealtime()
        val live = StreamingServerTranscriber(connection, object : VoiceTranscriber {
            override fun transcribe(wav: ByteArray): Result<String> = error("Unexpected batch replay")
            override fun close() {}
        })
        try {
            live.begin { if (it.isNotBlank()) { first.compareAndSet(0, SystemClock.elapsedRealtime() - began); partials.incrementAndGet() } }
            val pcm = audio.copyOfRange(44, audio.size)
            for (at in pcm.indices step 3200) {
                val end = minOf(at + 3200, pcm.size)
                val due = began + end / 32
                Thread.sleep((due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                live.audio(pcm.copyOfRange(at, end))
            }
            assertTrue("No live partials before recording finished", partials.get() > 0)
            val end = SystemClock.elapsedRealtime()
            val text = live.transcribe(audio).getOrThrow()
            val tail = SystemClock.elapsedRealtime() - end
            assertTrue(text.contains("dull light", ignoreCase = true))
            assertTrue(text.contains("widening", ignoreCase = true))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("first_partial_ms", first.get().toString())
                putString("partial_count", partials.get().toString())
                putString("final_after_audio_ms", tail.toString())
                putString("path", "phone -> plugin proxy -> CPU medium (precision recorded by helper health)")
            })
        } finally { live.close() }
    }
}
