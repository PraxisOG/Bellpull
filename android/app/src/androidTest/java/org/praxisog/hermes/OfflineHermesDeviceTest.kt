package org.praxisog.hermes

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OfflineHermesDeviceTest {
    @Test fun prepareOfflineReplyLostHandoffRetryAndSourceIsolation() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi; ui.guard()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "phase14-live-config.json")
        assumeTrue("Explicit local Hermes fixture config required", file.isFile)
        val config = JSONObject(file.readText()); check(file.delete())
        require(config.getString("url") == "http://127.0.0.1:18642")
        val loseReply = AtomicBoolean(false)
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.encodedPath.endsWith("/offline/handoff") && loseReply.compareAndSet(true, false)) {
                response.close(); throw IOException("Synthetic lost response after server commit")
            }
            response
        }.build()
        val client = HermesClient(config.getString("url"), { config.getString("key") }, httpClient = http)
        fun <T> ok(result: HermesResult<T>): T = when (result) {
            is HermesResult.Ok -> result.value
            is HermesResult.Err -> error("Live fixture request failed: ${result.error}")
        }
        val profile = "phase14-live-fixture"
        SessionCache(context, profile).clear()
        var connected = true
        val workspace = OfflineWorkspace(context, profile) { client.takeIf { connected } }
        val remote = AtomicReference<String?>(null)
        var source: String? = null
        fun idle() = ui.await("Offline workspace operation", 180000) { !workspace.state.value.busy }
        try {
            assertTrue(ok(client.capabilities()).features["bellpull_offline"] == true)
            source = ok(client.offlineHandoff(OfflineHandoff(offlineId(), title = "Phase 14 synthetic source", messages = listOf(
                OfflineHandoffMessage("user", "The robotics meeting is in Cobalt Orchard at 3 pm."),
                OfflineHandoffMessage("assistant", "Saved the meeting details."))))).sessionId
            val original = ok(client.completeHistory(source)).data
            idle(); workspace.prepare(source, "Phase 14 synthetic offline fork"); idle(); assertNull(workspace.state.value.error)
            connected = false
            workspace.draft("Which room is the robotics meeting in? Answer only the room name.")
            workspace.send(); idle(); assertNull(workspace.state.value.error)
            val answer = workspace.state.value.current!!.turns.last().text
            assertTrue(answer.contains("Cobalt Orchard"))
            connected = true; loseReply.set(true)
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme {
                    OfflineScreen(workspace.state.collectAsState().value, null, "", workspace, {}, { remote.set(it) }, {})
                } } }
                ui.tapText("Details"); ui.tapText("Continue in Hermes"); ui.tapText("Create Hermes branch")
                idle(); assertNotNull(workspace.state.value.error)
                val pending = workspace.state.value.current!!.pending!!
                assertEquals(pending, OfflineStorage(context, profile).fork(workspace.state.value.current!!.id)!!.pending)
                ui.tapText("Retry handoff"); ui.tapText("Create Hermes branch"); idle(); assertNull(workspace.state.value.error)
                assertNotNull(remote.get()); assertNull(workspace.state.value.current!!.pending)
                val imported = ok(client.completeHistory(remote.get()!!)).data
                assertEquals(answer, imported.last().content)
                assertTrue(imported.none { it.role == "tool" || !it.toolCalls.isNullOrEmpty() })
                assertEquals(original, ok(client.completeHistory(source)).data)
                assertFalse(ok(client.offlineHandoff(pending)).created)
                ui.screenshot("phase14-live-handoff")
                File(context.getExternalFilesDir(null), "phase14-live-report.json").writeText(JSONObject()
                    .put("offline_answer", answer).put("lost_response_retry_same_branch", true)
                    .put("source_unchanged", true).put("tools_replayed", false).put("imported_messages", imported.size).toString(2))
            }
        } finally {
            workspace.close()
            val pendingId = workspace.state.value.current?.pending?.operationId?.let { "bellpull_offline_$it" }
            listOfNotNull(source, remote.get() ?: pendingId).forEach { id ->
                http.newCall(okhttp3.Request.Builder().url(config.getString("url") + "/api/sessions/$id")
                    .header("Authorization", "Bearer " + config.getString("key")).delete().build()).execute().use {
                    check(it.isSuccessful || it.code == 404) { "Synthetic conversation cleanup failed" }
                }
            }
            client.close(); SessionCache(context, profile).clear(); file.delete()
        }
    }
}
