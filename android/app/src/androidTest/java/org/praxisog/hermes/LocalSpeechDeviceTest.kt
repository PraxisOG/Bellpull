package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import android.net.TrafficStats
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Direct audio only. Fixture package and pinned corpus hashes are mandatory. */
@RunWith(AndroidJUnit4::class)
class LocalSpeechDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun progress(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("speech_progress", message) })

    @Test fun directCorpusColdWarmChunkedAndSustained() = runBlocking {
        check(context.packageName == "org.praxisog.hermes.fixture")
        val arguments = InstrumentationRegistry.getArguments()
        val models = speechCatalog(context.assets.open("speech-models.json").bufferedReader().use { it.readText() })
        val model = models.single { it.id == arguments.getString("speechModel", "moonshine-tiny") }
        val files = SpeechModelFiles(context)
        var lastProgress = -1
        files.download(model) { current, total ->
            val percent = (current * 100 / total).toInt()
            if (percent / 10 != lastProgress / 10) { progress("Download ${model.id}: $percent%"); lastProgress = percent }
        }
        files.verify(model)
        if (arguments.getString("downloadOnly") == "true") {
            progress("${model.id}: download verified; no recognition run")
            return@runBlocking
        }
        val folder = File(context.filesDir, "speech-fixtures")
        val manifest = Json.parseToJsonElement(File(folder, "manifest.json").readText()).jsonObject
        val fixtures = manifest.getValue("fixtures").jsonArray.map { entry ->
            val row = entry.jsonObject
            val filename = row.getValue("file").jsonPrimitive.content
            require(filename.matches(Regex("[a-z-]+\\.wav")))
            val audio = SpeechAudio.readWav(File(folder, filename).readBytes())
            assertEquals(row.getValue("sha256").jsonPrimitive.content, audio.sourceSha256)
            SpeechFixture(row.getValue("id").jsonPrimitive.content, row.getValue("reference").jsonPrimitive.content,
                audio, row.getValue("last_word_end_ms").jsonPrimitive.double)
        }
        val thermals = SpeechThermals(context)
        val tuning = SpeechTuning()
        val runtime = SpeechModelRuntime(files::verify) { MoonshineSpeechEngine(it, files.directory(it), tuning) }
        val benchmarker = SpeechBenchmarker(runtime, thermals)
        val results = mutableListOf<SpeechRunMeasurement>()
        val output = File(File(context.noBackupFilesDir, "speech-benchmarks"), "device-${model.id}.json")
        check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
        suspend fun measure(fixture: SpeechFixture, feed: SpeechFeed, repetition: Int) {
            val row = benchmarker.measure(model, fixture, feed, repetition)
            results += row
            val pending = File(output.parentFile, output.name + ".tmp")
            pending.writeText(speechMeasurementsJson(results).toString())
            check(pending.renameTo(output))
            progress("${model.id} ${fixture.id} ${feed.name} #$repetition: ${"%.1f".format(row.elapsedMs)} ms; ${row.wordEdits}/${row.referenceWords} word edits")
        }
        try {
            for (feed in SpeechFeed.entries) {
                for (fixture in fixtures) {
                    runtime.close()
                    repeat(4) { measure(fixture, feed, it) }
                }
            }
            if (arguments.getString("sustained", "true") == "true") {
                progress("${model.id}: sustained CPU test, at least 180 seconds")
                val began = android.os.SystemClock.elapsedRealtime()
                var repetition = 4
                while (android.os.SystemClock.elapsedRealtime() - began < 180_000) {
                    measure(fixtures.first(), SpeechFeed.THROUGHPUT, repetition++)
                }
            }
            assertTrue("Clean speech produced no transcript", results.any { it.fixture == "clean" && it.transcript.isNotBlank() })
            progress("Saved ${results.size} results: ${output.name}; ${thermals.snapshot()}")
        } finally { runtime.close() }
    }

    @Test fun defaultConversationPauseGateOnDirectCorpus() {
        check(context.packageName == "org.praxisog.hermes.fixture")
        val folder = File(context.filesDir, "speech-fixtures")
        val manifest = Json.parseToJsonElement(File(folder, "manifest.json").readText()).jsonObject
        val results = manifest.getValue("fixtures").jsonArray.map { entry ->
            val row = entry.jsonObject
            val audio = SpeechAudio.readWav(File(folder, row.getValue("file").jsonPrimitive.content).readBytes())
            assertEquals(row.getValue("sha256").jsonPrimitive.content, audio.sourceSha256)
            val lastWord = row.getValue("last_word_end_ms").jsonPrimitive.double
            val gate = VoiceGate(1600)
            val endings = mutableListOf<Double>()
            for (offset in audio.samples.indices step 320) {
                val end = minOf(offset + 320, audio.samples.size)
                val frame = ShortArray(end - offset) { (audio.samples[offset + it] * 32768).toInt().coerceIn(-32768, 32767).toShort() }
                if (gate.accept(frame, frame.size).complete != null) endings += end * 1000.0 / audio.sampleRate
            }
            val premature = endings.count { it < lastWord - 150 }
            val name = row.getValue("id").jsonPrimitive.content
            progress("Bellpull pause gate $name: $premature premature turns; endings=$endings ms; last word=$lastWord ms")
            assertEquals("Premature turn in $name", 0, premature)
            assertEquals("Expected one completed turn in $name", 1, endings.size)
            buildJsonObject {
                put("fixture", name); put("audio_sha256", audio.sourceSha256); put("pause_ms", 1600)
                put("last_word_end_ms", lastWord); put("premature_turns", premature)
                put("turn_end_ms", endings.single()); put("last_word_to_capture_end_ms", endings.single() - lastWord)
            }
        }
        val output = File(context.noBackupFilesDir, "speech-benchmarks/pause-gate.json")
        check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
        output.writeText(JsonArray(results).toString())
    }

    @Test fun nativeModelSwitchingTuningAndLocalVoiceReviewWithoutNetwork() {
        check(context.packageName == "org.praxisog.hermes.fixture")
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime() + 60_000
            while (!predicate()) {
                check(android.os.SystemClock.elapsedRealtime() < until) { "Local voice condition timed out" }
                Thread.sleep(50)
            }
        }
        lateinit var lab: SpeechLab
        main { lab = BellpullRuntime.get(context).speechLab }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val uid = android.os.Process.myUid()
            val beforeRx = TrafficStats.getUidRxBytes(uid)
            val beforeTx = TrafficStats.getUidTxBytes(uid)
            for (id in listOf("moonshine-tiny", "moonshine-small", "moonshine-medium", "moonshine-tiny")) {
                main { lab.choose(lab.models.single { it.id == id }) }
                await { var finished = false; main { finished = !lab.busy }; finished }
                main { assertEquals("CPU (ONNX Runtime)", lab.actualBackend); assertEquals(id, lab.runtime.loadedModel) }
            }
            main { lab.tune(SpeechTuning(600, .55f)) }
            await { var done = false; main { done = !lab.busy }; done }
            main {
                assertNull(lab.runtime.loadedModel)
                assertEquals(SpeechTuning(600, .55f), SpeechExperimentSettings(context).tuning(lab.selectedId))
            }
            main { lab.tune(SpeechTuning()) }
            await { var done = false; main { done = !lab.busy }; done }
            val output = AndroidVoiceOutput(context, 1.05f)
            val capture = object : VoiceCapture {
                override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit) = true
                override fun close() {}
            }
            val saved = AtomicReference<String>()
            val voice = VoiceSession("offline-fixture", "offline-fixture", scope,
                object : VoiceConversation {
                    override val selected = true
                    override val ready = true
                    override fun run() = VoiceRun()
                    override fun send(text: String): Boolean = error("Review mode must not send")
                    override fun cancel() {}
                }, VoiceConfiguration(), output, capture, transcriber = lab::voiceTranscriber, onTranscript = saved::set)
            try {
                main { voice.start(VoiceMode.DICTATION) }
                await { var ready = false; main { ready = voice.state == VoiceState.LISTENING }; ready }
                val audio = SpeechAudio.readWav(File(context.filesDir, "speech-fixtures/clean.wav").readBytes())
                main {
                    for (offset in audio.samples.indices step 320) {
                        val end = minOf(offset + 320, audio.samples.size)
                        val frame = ShortArray(end - offset) { (audio.samples[offset + it] * 32768).toInt().coerceIn(-32768, 32767).toShort() }
                        voice.frame(frame, frame.size)
                    }
                }
                await { var done = false; main { done = voice.state == VoiceState.REVIEW || voice.state == VoiceState.PAUSED }; done }
                main {
                    assertEquals(voice.notice, VoiceState.REVIEW, voice.state)
                    assertTrue(voice.transcript.isNotBlank()); assertEquals(voice.transcript, saved.get())
                }
            } finally { main { voice.close() } }
            val afterRx = TrafficStats.getUidRxBytes(uid)
            val afterTx = TrafficStats.getUidTxBytes(uid)
            if (beforeRx >= 0 && beforeTx >= 0) {
                assertEquals("Local recognition received network bytes", beforeRx, afterRx)
                assertEquals("Local recognition sent network bytes", beforeTx, afterTx)
            }
            val medium = lab.models.single { it.id == "moonshine-medium" }
            main { lab.delete(medium) }
            await { var done = false; main { done = !lab.busy }; done }
            main {
                assertFalse(lab.files.installed(medium)); assertFalse(lab.files.hasPartial(medium))
                assertTrue(lab.files.installed(lab.models.single { it.id == "moonshine-tiny" }))
                assertTrue(lab.files.installed(lab.models.single { it.id == "moonshine-small" }))
            }
            progress("Native switching, saved tuning and local voice review passed; UID network counters RX $beforeRx → $afterRx, TX $beforeTx → $afterTx")
        } finally { scope.cancel(); lab.runtime.close() }
    }
}
