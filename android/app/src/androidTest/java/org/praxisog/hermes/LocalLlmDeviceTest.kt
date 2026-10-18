package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses only the disposable package and synthetic prompts. No Hermes connection. */
class LocalLlmDeviceTest {
    private val ui = DeviceFixtureUi
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val lab get() = LocalLlmLab.get(context)
    private fun idle(timeout: Long = 180000) = ui.await("Local operation did not finish: ${lab.state.value.status}", timeout) { !lab.state.value.busy }
    private fun success() { assertNull(lab.state.value.error) }
    private fun load() { ui.main { lab.load() }; idle(); success(); assertTrue(lab.state.value.ready) }
    private fun send(prompt: String) { ui.main { lab.draft(prompt); lab.send() }; idle(); success() }
    @Test fun cpuChatBenchmarkCancellationAndLifecycle() = ui.withWindows {
        val source = File(context.filesDir, "model.gguf")
        assertTrue("Stage a GGUF in the fixture private files directory", source.isFile)
        ActivityScenario.launch<LocalLlmActivity>(Intent(context, LocalLlmActivity::class.java)).use { scenario ->
            ui.main { lab.import(Uri.fromFile(source), "Gemma 4 E2B Q4_0.gguf") }; idle()
            success()
            assertEquals("fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634", lab.state.value.models.last().sha256)
            load()
            scenario.recreate()
            assertTrue("Rotation must retain the loaded engine", lab.state.value.ready)
            send("Remember this code word: Cobalt Orchard. Reply with exactly OK.")
            send("What was the code word? Reply only with the code word.")
            assertTrue(lab.state.value.messages.last().text.contains("Cobalt Orchard"))
            ui.screenshot("phase13-local-chat")
            ui.main { lab.benchmark(false) }; idle(240000); success()
            val report = JSONObject(lab.state.value.report)
            val runs = report.getJSONArray("runs")
            assertEquals(6, runs.length())
            assertEquals("one two three", runs.getJSONObject(0).getString("text").trim())
            assertEquals("5", runs.getJSONObject(1).getString("text").trim())
            assertTrue(runs.getJSONObject(2).getString("text").contains("Cobalt Orchard"))
            assertTrue(runs.getJSONObject(0).getDouble("generation_tps") > 0)
            File(context.getExternalFilesDir(null), "phase13-cpu-report.json").writeText(lab.state.value.report)
            load()
            ui.main { lab.newChat(); lab.draft("Write a long story about a robot. " + "A robot explores a garden. ".repeat(140)); lab.send() }
            SystemClock.sleep(350)
            val stopAt = SystemClock.elapsedRealtime()
            ui.main { lab.stop() }; idle(15000)
            val stopMs = SystemClock.elapsedRealtime() - stopAt
            assertTrue("Stop took $stopMs ms", stopMs < 10000)
            assertFalse(lab.state.value.ready)
            assertEquals("stopped", lab.state.value.messages.last().status)
            load()
            send("Reply with exactly: recovered")
            assertEquals("recovered", lab.state.value.messages.last().text.trim())
            ui.main { lab.newChat(); lab.draft("Write a long story about a lighthouse."); lab.send() }
            SystemClock.sleep(1000)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            idle(15000)
            ui.await("Background must unload model") { !lab.state.value.ready }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertFalse(lab.state.value.ready)
            File(context.getExternalFilesDir(null), "phase13-lifecycle.json").writeText(JSONObject().put("stop_ms", stopMs).put("recreation_retained_model", true).put("background_unloaded", true).toString(2))
        }
    }
    @Test fun vulkanMustPassRepeatedTokenComparison() = ui.withWindows {
        ActivityScenario.launch<LocalLlmActivity>(Intent(context, LocalLlmActivity::class.java)).use {
            ui.await("Model scan") { lab.state.value.selected != null }
            ui.main { lab.benchmark(true) }; idle(600000)
            File(context.getExternalFilesDir(null), "phase13-gpu-report.json").writeText(lab.state.value.report)
            val report = JSONObject(lab.state.value.report)
            val passed = report.optBoolean("gpu_exact_match", false)
            assertEquals(passed, lab.state.value.gpuValidatedFor != null)
            assertFalse(lab.state.value.ready)
            if (!passed) {
                ui.main { lab.load(true) }; idle()
                assertNotNull("Unvalidated GPU must fail visibly", lab.state.value.error)
                assertFalse(lab.state.value.ready)
            }
            load()
            send("What is 2 plus 3? Answer with one number.")
            assertEquals("5", lab.state.value.messages.last().text.trim())
            ui.screenshot("phase13-gpu-result")
        }
    }
    @Test fun e4bBenchmarkAndOfflineChat() = ui.withWindows {
        ActivityScenario.launch<LocalLlmActivity>(Intent(context, LocalLlmActivity::class.java)).use {
            val source = File(context.filesDir, "e4b.gguf")
            ui.main { lab.import(Uri.fromFile(source), "Gemma 4 E4B Q4_0.gguf") }; idle(); success()
            assertEquals("676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee", lab.state.value.models.last().sha256)
            ui.main { lab.benchmark(false) }; idle(240000); success()
            File(context.getExternalFilesDir(null), "phase13-e4b-cpu-report.json").writeText(lab.state.value.report)
            load()
            send("What is 2 plus 3? Answer with one number.")
            assertEquals("5", lab.state.value.messages.last().text.trim())
            ui.main { lab.newChat(); lab.draft("Repeat only: hello 🌙 café 日本語"); lab.send() }; idle(); success()
            assertTrue(lab.state.value.messages.last().text.contains("🌙"))
            ui.main { lab.newChat(); lab.draft("word ".repeat(2500)); lab.send() }; idle()
            assertTrue(lab.state.value.error.orEmpty().contains("2048-token"))
            load()
            send("Reply with exactly: offline recovery")
            assertEquals("offline recovery", lab.state.value.messages.last().text.trim())
            ui.tapText("Hide setup")
            val editor = checkNotNull(ui.find { it.isEditable })
            assertTrue(editor.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Reply with exactly: button works") }))
            ui.tapText("Send locally")
            ui.await("Send button did not submit") { lab.state.value.busy || lab.state.value.messages.last().text.contains("button works") }
            idle(); success()
            assertEquals("button works", lab.state.value.messages.last().text.trim())
            ui.screenshot("phase13-e4b-offline-chat")
        }
    }
}
