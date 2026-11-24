package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test

class ConversationVoiceOutputTest {
    private class Speaker(private val label: String) : VoiceOutput {
        lateinit var ready: (String) -> Unit
        lateinit var error: (String) -> Unit
        lateinit var start: () -> Unit
        lateinit var finish: () -> Unit
        val phrases = mutableListOf<String>()
        override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) { ready = onReady; error = onError }
        fun loaded() = ready(label)
        override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { phrases.add(text); start = started; finish = complete }
        override fun stop() {}
        override fun close() {}
    }
    private val server = Speaker("server")
    private val device = Speaker("device")
    private var switches = 0
    private val output = ConversationVoiceOutput(server, device) { switches++ }

    @Test fun switchesBeforeFirstFrameAndIgnoresStaleCompletion() {
        var prepared = 0; var starts = 0; var ends = 0
        output.prepare({ prepared++ }, { fail(it) }); server.loaded()
        output.speak("one", { starts++ }, { ends++ }); server.error("offline")
        server.start(); server.finish()
        assertEquals(0, starts); assertEquals(0, ends)
        device.loaded(); assertEquals(listOf("one"), device.phrases)
        device.start(); device.finish()
        assertEquals(1, starts); assertEquals(1, ends); assertEquals(1, switches); assertEquals(1, prepared)
        assertNotNull(output.routeNotice)
    }
    @Test fun neverReplaysAnAudiblePhraseAndCanReadTheNextOne() {
        var message = ""
        output.prepare({}, { message = it }); server.loaded()
        output.speak("spoken", {}, { fail("The interrupted sentence did not finish") }); server.start(); server.error("offline")
        assertTrue(message.contains("stopped during playback")); assertEquals(emptyList<String>(), device.phrases)
        output.speak("next", {}, {}); device.loaded(); assertEquals(listOf("next"), device.phrases)
    }
    @Test fun stopWhileLocalVoiceLoadsDoesNotSpeakAnOldPhrase() {
        output.prepare({}, { fail(it) }); server.loaded()
        output.speak("old", {}, {}); server.error("offline"); val stale = device.ready
        output.stop(); stale("late"); assertTrue(device.phrases.isEmpty())
        output.speak("new", {}, {}); device.loaded(); assertEquals(listOf("new"), device.phrases)
        output.close(); server.error("stale"); assertEquals(1, switches)
    }
}
