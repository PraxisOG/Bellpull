package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Synthetic fixture only. E2B is staged into this package by the test runner. */
class OfflineDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ui = DeviceFixtureUi
    @Test fun olderServerCannotLockTheLocalChatWithAPendingHandoff() {
        ui.guard()
        val profile = "offline-old-server-fixture"
        SessionCache(context, profile).clear()
        val fork = OfflineFork(turns = listOf(OfflineTurn(role = "user", text = "Saved offline question")))
        OfflineStorage(context, profile).save(fork)
        val http = org.praxisog.hermes.core.HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"features\":{}}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = org.praxisog.hermes.core.HermesClient("http://127.0.0.1:1", { "fixture-only" }, httpClient = http)
        val workspace = OfflineWorkspace(context, profile) { client }
        try {
            ui.await("legacy setup") { !workspace.state.value.busy }
            workspace.open(fork.id); ui.await("legacy fork") { !workspace.state.value.busy }
            workspace.handoff { fail("Unsupported server must not receive a handoff") }
            ui.await("legacy rejection") { !workspace.state.value.busy }
            assertNull(workspace.state.value.current!!.pending)
            assertTrue(workspace.state.value.error.orEmpty().contains("0.5.0"))
            workspace.draft("Still editable"); assertEquals("Still editable", workspace.state.value.current!!.draft)
        } finally { workspace.close(); client.close(); SessionCache(context, profile).clear() }
    }
    @Test fun e2bCallsBoundedSourceSearchTool() = ui.withWindows {
        ui.guard()
        val profile = "offline-tool-fixture"
        SessionCache(context, profile).clear()
        val storage = OfflineStorage(context, profile)
        storage.save(OfflineSource("tool-source", "Tool fixture", listOf(Message(1, role = "user",
            content = "Blue Lantern is the robotics project. Its meeting takes place in Cobalt Orchard."))))
        val workspace = OfflineWorkspace(context, profile) { null }
        fun idle() = ui.await("tool operation", 180000) { !workspace.state.value.busy }
        try {
            idle(); workspace.create("tool-source"); idle()
            workspace.draft("First use search_chat with the query Blue Lantern. After reading its result, tell me which room the project meeting uses.")
            workspace.send(); idle()
            assertNull(workspace.state.value.error)
            val answer = workspace.state.value.current!!.turns.last()
            File(context.getExternalFilesDir(null), "phase14-tool-report.json").writeText(JSONObject()
                .put("answer", answer.text).put("tools", org.json.JSONArray(answer.tools)).put("metrics", JSONObject(answer.metrics)).toString(2))
            assertTrue("Model should execute the requested local retrieval", answer.tools.contains("search_chat"))
            assertTrue(answer.tools.size <= 2)
            assertTrue(answer.text.contains("Cobalt Orchard"))
            assertTrue(JSONObject(answer.metrics).getInt("total_generated_tokens") <= 384)
        } finally { workspace.close(); SessionCache(context, profile).clear() }
    }
    @Test fun encryptedStorageAndProfileIsolation() {
        ui.guard()
        val a = "offline-test-a"; val b = "offline-test-b"
        val first = OfflineStorage(context, a); val second = OfflineStorage(context, b)
        val source = OfflineSource("same-source", "Private fixture", listOf(Message(1, role = "user", content = "secret Cobalt Orchard")))
        val fork = OfflineFork(source = source, draft = "unfinished draft", turns = listOf(OfflineTurn(role = "assistant", text = "partial", status = "generating")))
        first.save(source); first.save(fork)
        assertEquals(fork, OfflineStorage(context, a).fork(fork.id)); assertNull(second.fork(fork.id))
        val bytes = File(context.filesDir, "bellpull-files/$a/offline/fork-${digest(fork.id)}").readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Cobalt Orchard"))
        val copied = File(context.filesDir, "bellpull-files/$b/offline/fork-${digest(fork.id)}")
        copied.writeBytes(bytes)
        assertTrue(runCatching { second.fork(fork.id) }.isFailure)
        copied.delete()
        val workspace = OfflineWorkspace(context, a) { null }
        ui.await("restore") { !workspace.state.value.busy }
        assertEquals("interrupted", workspace.state.value.forks.single().turns.single().status)
        workspace.open(fork.id); ui.await("open restored fork") { !workspace.state.value.busy }
        workspace.draft("Draft survives immediate navigation")
        workspace.back(); workspace.open(fork.id)
        ui.await("reopen draft") { !workspace.state.value.busy }
        assertEquals("Draft survives immediate navigation", workspace.state.value.current!!.draft)
        workspace.close()
        SessionCache(context, a).clear(); SessionCache(context, b).clear()
        assertTrue(OfflineStorage(context, a).forks().isEmpty())
    }
    @Test fun realEmbeddingsAndE2bThinkingCacheAndCancellation() = ui.withWindows {
        ui.guard()
        val profile = "offline-cpu-fixture"
        val runtime = BellpullRuntime.get(context)
        ui.main { runtime.disconnect() }
        SessionCache(context, profile).clear()
        ui.main { runtime.prepare(profile) }
        val workspace = checkNotNull(runtime.offlineWorkspace)
        val storage = OfflineStorage(context, profile)
        storage.save(OfflineSource("source", "Offline device fixture", (1..40).map { Message(it, role = "user", content = "Shopping list number $it: bread, milk, oranges.") } +
            Message(100, role = "assistant", content = "The robotics meeting is in Cobalt Orchard at 3 pm. Its project name is Blue Lantern.")))
        ui.await("workspace ready") { !workspace.state.value.busy }
        workspace.create("source")
        fun idle(timeout: Long = 240000) = ui.await("offline operation", timeout) { !workspace.state.value.busy }
        fun success() { assertNull(workspace.state.value.error) }
        idle(); success()
        val started = SystemClock.elapsedRealtime(); workspace.prepareCurrent(); idle(); success()
        val indexMs = SystemClock.elapsedRealtime() - started
        workspace.search("Where do the robot builders gather?"); idle(); success()
        assertEquals(100, workspace.state.value.searchResults.first().messageId)
        val lab = LocalLlmLab.get(context)
        ui.await("E2B model scan") { lab.state.value.models.isNotEmpty() }
        ActivityScenario.launch<OfflineChatActivity>(Intent(context, OfflineChatActivity::class.java)).use { scenario ->
            workspace.open(workspace.state.value.forks.first().id); idle()
            workspace.draft("Which room is the robotics meeting in? Answer only the room name.")
            workspace.send(); idle(); success()
            val first = workspace.state.value.current!!.turns.last()
            assertTrue(first.text.contains("Cobalt Orchard")); assertTrue(first.thought.isEmpty())
            assertEquals("complete", first.status)
            scenario.recreate()
            assertEquals(first, workspace.state.value.current!!.turns.last())
            workspace.draft("What time is that meeting? Answer only the time."); workspace.send(); idle(); success()
            val second = workspace.state.value.current!!.turns.last()
            assertTrue(second.text.contains("3")); assertTrue(JSONObject(second.metrics).getInt("reused_tokens") > 0)
            workspace.configure(true, ""); idle()
            workspace.draft("Think through this: a train leaves at 9:17, travels 48 minutes, waits 13 minutes, then travels 56 minutes. What time does it arrive? Answer concisely."); workspace.send(); idle(); success()
            val thinking = workspace.state.value.current!!.turns.last()
            File(context.getExternalFilesDir(null), "phase14-thinking-probe.json").writeText(JSONObject()
                .put("answer", thinking.text).put("thought", thinking.thought).put("metrics", JSONObject(thinking.metrics)).toString(2))
            assertTrue(thinking.text.contains("11:14"))
            assertTrue(JSONObject(thinking.metrics).getBoolean("thinking_marker"))
            assertTrue("Thinking must reach the native template", thinking.thought.isNotBlank())
            ui.screenshot("phase14-offline-chat")
            workspace.draft("Write a long story about a robot exploring a garden."); workspace.send()
            SystemClock.sleep(1000); val stopAt = SystemClock.elapsedRealtime(); workspace.stop(); idle(15000)
            val stopMs = SystemClock.elapsedRealtime() - stopAt
            assertEquals("interrupted", workspace.state.value.current!!.turns.last().status)
            assertTrue(stopMs < 10000)
            val saved = OfflineStorage(context, profile).fork(workspace.state.value.current!!.id)!!
            assertEquals(workspace.state.value.current!!.turns, saved.turns)
            File(context.getExternalFilesDir(null), "phase14-device-report.json").writeText(JSONObject()
                .put("index_41_messages_ms", indexMs).put("stop_ms", stopMs)
                .put("first", JSONObject(first.metrics)).put("cached_second", JSONObject(second.metrics))
                .put("thinking", JSONObject(thinking.metrics)).put("answer", thinking.text).toString(2))
        }
        ui.main { runtime.disconnect() }
    }
}
