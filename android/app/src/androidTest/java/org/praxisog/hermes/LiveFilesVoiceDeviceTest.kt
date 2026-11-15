package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic files and real GPU speech through a disposable stock Hermes proxy. */
class LiveFilesVoiceDeviceTest {
    private val ui = DeviceFixtureUi
    private val origin = "http://127.0.0.1:18646"
    private val key = "bellpull-stock-device-fixture"
    @Test fun nativeFilesSettingsAndChecklistRoundTrip() = ui.withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secure = SecureConnectionStore(context)
        val runtime = BellpullRuntime.get(context)
        ui.main { runtime.disconnect() }; secure.clear()
        secure.activate(secure.add("Files fixture", origin, key).id)
        val api = HermesClient(origin, { key })
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.awaitMain("Live files did not connect") { runtime.liveFiles?.entries?.size == 2 }
                val files = checkNotNull(runtime.liveFiles)
                if (ui.text("Files") == null) ui.tap(checkNotNull(ui.find {
                    it.contentDescription?.toString() in setOf("Back to sessions", "Show sessions", "Files list")
                }))
                ui.tapText("Files")
                ui.tapText("Groceries.md")
                ui.await("Grocery viewer missing") { ui.text("Checklist editing allowed") != null }
                ui.screenshot("files-checklist")
                val initial = checkNotNull(files.snapshot)
                val task = initial.tasks.first()
                ui.awaitMain("File did not become editable") { files.fresh && !files.loading && files.pending == null }
                ui.tapText("Milk")
                ui.screenshot("files-after-check")
                try { ui.awaitMain("Checkbox was not saved") { files.fresh && !files.busy && files.snapshot?.tasks?.first()?.checked == !task.checked } }
                catch (error: AssertionError) { throw AssertionError("${error.message}: loading=${files.loading} busy=${files.busy} fresh=${files.fresh} problem=${files.problem} pending=${files.pending} snapshot=${files.snapshot}", error) }
                ui.tapText("New list item")
                val field = checkNotNull(ui.find { it.className?.toString() == "android.widget.EditText" && it.isFocused })
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Apples from phone") })
                ui.tapText("Add item")
                ui.awaitMain("New list item was not saved") { files.snapshot?.text?.contains("- [ ] Apples from phone") == true && !files.busy }
                assertTrue((api.liveFile(initial.id) as HermesResult.Ok).value!!.text.contains("- [ ] Apples from phone"))
                val pinned = checkNotNull(files.selected).pinned
                ui.main { files.pin(checkNotNull(files.selected)) }
                ui.awaitMain("Pin did not persist") { files.selected?.pinned == !pinned }
                ui.tapText("Settings")
                ui.await("Recent files missing from Settings") { ui.text("Recent files") != null }
                ui.screenshot("settings-files-gpu")
                ui.tapText("See all files")
                ui.tapText("Read only.md")
                ui.await("Read-only file missing") { ui.text("This cannot be changed") != null }
                val readonly = checkNotNull(files.snapshot)
                assertEquals("read", readonly.access)
                ui.tap(checkNotNull(ui.text("This cannot be changed")))
                ui.main { assertNull(files.pending); assertEquals(readonly.text, files.snapshot?.text) }
                assertNull(ui.text("Add item"))
                ui.screenshot("files-read-only")
                ui.main { files.open(initial.id) }
                ui.awaitMain("Reopening did not refresh") { files.fresh && files.snapshot?.id == initial.id }
                assertTrue(files.snapshot!!.text.contains("Apples from phone"))
            }
        } finally { api.close(); ui.main { runtime.disconnect() }; secure.clear() }
    }

    @Test fun serverPlaybackPrefetchDurationAndStop() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.fixture")
        val connection = ServerConnection(ServerProfile("speech-fixture", "Speech fixture", origin), key)
        val transport = ProfileSpeechTransport(connection)
        val synthesis = CopyOnWriteArrayList<ServerSpeechTiming>()
        val generationStarts = CopyOnWriteArrayList<Double>()
        var epoch = System.nanoTime()
        val runtime = LocalTtsRuntime({}) { object : LocalTtsEngine {
            val engine = ServerMossEngine(transport) { synthesis.add(it) }
            override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
                generationStarts.add((System.nanoTime() - epoch) / 1e6)
                return engine.synthesize(text, config, sink)
            }
            override fun close() = engine.close()
        } }
        val output = LocalVoiceOutput(runtime, VoiceOutputConfiguration(engine = VoiceOutputEngine.SERVER_MOSS, volume = .08f),
            1f, { false }, player = { rate, volume -> voicePcmPlayer(VoiceOutputEngine.SERVER_MOSS, rate, volume) }, cancelSynthesis = transport::cancel)
        val ready = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val starts = CopyOnWriteArrayList<Double>(); val ends = CopyOnWriteArrayList<Double>()
        val phrases = listOf("Milk and apples are on the shopping list.", "You can check them off as you walk through the store.", "Every change is saved in the original file.")
        try {
            withContext(Dispatchers.Main) { output.prepare({ ready.complete(Unit) }, { done.completeExceptionally(IllegalStateException(it)) }) }
            withTimeout(30_000) { ready.await() }; epoch = System.nanoTime()
            fun speak(index: Int) {
                output.speak(phrases[index], { starts.add((System.nanoTime() - epoch) / 1e6) }, {
                    ends.add((System.nanoTime() - epoch) / 1e6)
                    if (index == phrases.lastIndex) done.complete(Unit) else speak(index + 1)
                })
                phrases.getOrNull(index + 1)?.let(output::prefetch)
            }
            withContext(Dispatchers.Main) { speak(0) }
            withTimeout(60_000) { done.await() }
            assertEquals(3, starts.size); assertEquals(3, synthesis.size)
            assertTrue("Lookahead must overlap playback", generationStarts[1] < ends[0])
            assertTrue("24 kHz audio must retain its duration", (ends.last() - starts.first()) / 1000 > synthesis.sumOf { it.audioSeconds } * .85)
            val gaps = (1..phrases.lastIndex).map { starts[it] - ends[it - 1] }
            val begun = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { output.speak(phrases.joinToString(" "), { begun.complete(Unit) }, {}); output.prefetch(phrases[0]) }
            withTimeout(30_000) { begun.await() }
            val stop = withContext(Dispatchers.Main) { val before = System.nanoTime(); output.stop(); (System.nanoTime() - before) / 1e6 }
            assertTrue("Stop should return promptly", stop < 500)
            val result = JSONObject().put("first_playback_ms", starts.first()).put("gaps_ms", JSONArray(gaps))
                .put("first_pcm_ms", JSONArray(synthesis.take(3).map { it.firstPcmMs }))
                .put("audio_seconds", synthesis.take(3).sumOf { it.audioSeconds }).put("stop_ms", stop)
            File(app.filesDir, "server-voice-streaming.json").writeText(result.toString(2))
            ui.instrumentation.sendStatus(0, Bundle().apply { putString("server_voice", result.toString()) })
        } finally { withContext(Dispatchers.Main) { output.close() }; withContext(Dispatchers.IO) { runtime.unload() }; transport.close() }
    }
}
