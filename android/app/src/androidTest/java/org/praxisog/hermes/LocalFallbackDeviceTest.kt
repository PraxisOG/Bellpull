package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*
import java.io.File

/** Real CPU inference and navigation in the disposable package only. */
class LocalFallbackDeviceTest {
    private val ui = DeviceFixtureUi
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun firstQuestionWaitsForBackgroundLoadingWithoutLosingTheDraft() = ui.withWindows {
        val profile = "fallback-first-send"
        SessionCache(context, profile).clear()
        LocalFallbackSettings(context).update(LocalFallbackConfiguration())
        val workspace = OfflineWorkspace(context, profile) { null }
        val lab = LocalLlmLab.get(context)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use {
                ui.await("new workspace") { !workspace.state.value.busy && lab.state.value.scanned && !lab.state.value.busy }
                workspace.create(null); ui.await("new local chat") { !workspace.state.value.busy }
                lab.unload(); ui.await("unload before cold start") { !lab.state.value.busy && !lab.state.value.ready }
                val loading = lab.prepareOffline(2048)
                assertTrue(lab.state.value.busy)
                workspace.draft("What is 2 plus 3? Answer with one number.")
                workspace.send()
                runBlocking { loading.await() }
                ui.await("queued first answer", 120000) { !workspace.state.value.busy }
                assertNull(workspace.state.value.error)
                assertEquals(2, workspace.state.value.current!!.turns.size)
                assertTrue(workspace.state.value.current!!.turns.last().text.contains("5"))
                assertTrue(workspace.state.value.current!!.draft.isEmpty())
            }
        } finally { workspace.close(); SessionCache(context, profile).clear() }
    }

    @Test fun compactionKeepsCorrectionsConstraintsAndUnfinishedWork() = ui.withWindows {
        val profile = "fallback-quality-test"
        SessionCache(context, profile).clear()
        LocalFallbackSettings(context).update(LocalFallbackConfiguration(contextTokens = 2048))
        val storage = OfflineStorage(context, profile)
        val options = listOf(
            "a riverside walk with uneven cobbles and very little shelter from rain",
            "a small history museum whose older entrance has a steep staircase",
            "a botanical garden on the edge of town requiring two bus transfers",
            "a crowded market with stalls selling ceramics and secondhand books",
            "a guided boat trip which does not guarantee a level boarding ramp",
            "a hilltop viewpoint reached by a narrow winding path through woodland",
            "an art gallery with a quiet cafe and some temporary exhibitions",
            "a music performance that starts late and finishes after the last train",
            "a pottery workshop that needs advance payment and a minimum group size",
            "a food tour that might be difficult to adapt for dietary restrictions",
            "a lighthouse visit with a long exposed walk from the car park",
            "a library tour focused on local maps and the history of the railway",
            "a sculpture park whose paths can become muddy after heavy rainfall",
            "a seaside aquarium near a busy interchange and an expensive car park",
            "a street photography walk which would involve standing for a long time",
            "an architecture tour crossing several pedestrian bridges and underpasses",
        )
        val messages = mutableListOf(Message(1, role = "user", content =
            "This is a fictional planning test. We are planning a day trip on November 12. Sam uses a wheelchair: every chosen venue and transport connection must be step-free. The initial budget is 640 dollars and our tentative train leaves at 10:40. Nothing has been booked. Keep these facts for the plan."))
        options.forEachIndexed { i, option ->
            messages.add(Message(messages.size + 1, role = "user", content = "Could we consider $option? " +
                "I am brainstorming rather than choosing it. We would need to check practical access, travel time, opening hours and the cancellation policy. " +
                "I do not want an attractive description to be mistaken for a confirmed booking. We should leave space for a relaxed lunch and avoid rushing between stops. " +
                "Can you describe the tradeoffs without adding this option to the final plan?"))
            messages.add(Message(messages.size + 1, role = "assistant", content = "That remains an unselected idea. " +
                "It could add variety, but it would consume travel time and might be tiring. Accessibility would need direct confirmation with the operator. " +
                "We should compare the total travel cost with our budget and make sure there is an easy route back to the station. " +
                "No tickets have been bought and no reservation has been made. We can discard this idea without changing the agreed constraints."))
            if (i == 7) messages.add(Message(messages.size + 1, role = "user", content =
                "Correction to the actual plan: the budget is now 720 dollars, replacing 640. The train is confirmed for 11:15, replacing the tentative 10:40. The travel date is still November 12. Sam still needs step-free access. Do not book anything."))
        }
        messages.add(Message(messages.size + 1, role = "user", content =
            "Final decision: skip all the brainstormed outings. We will have a quiet lunch near the station, then return home. The remaining task is for Lee to confirm a wheelchair-accessible taxi. We are waiting for Lee; that taxi is not booked. Please preserve the corrected train and budget from earlier."))
        storage.save(OfflineSource("quality", "SYNTHETIC TEST · trip planning", messages))
        val workspace = OfflineWorkspace(context, profile) { null }
        fun idle() = ui.await("quality compaction", 600000) { !workspace.state.value.busy }
        try {
            idle()
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme {
                    OfflineScreen(workspace.state.collectAsState().value, null, "", workspace, {}, {}, {})
                } } }
                workspace.requestFork("quality", "SYNTHETIC TEST · trip planning", download = false); idle()
                val sourceTokens = workspace.state.value.forkReview!!.tokens
                workspace.compactCurrent(); idle()
                assertNull(workspace.state.value.error)
                val summary = workspace.state.value.current!!.summary
                File(context.getExternalFilesDir(null), "fallback-quality.json").writeText(JSONObject()
                    .put("source_tokens", sourceTokens).put("context", 2048).put("summary", summary).toString(2))
                assertTrue("Corrected budget: $summary", summary.contains("720"))
                assertTrue("Corrected train: $summary", summary.contains("11:15"))
                assertTrue("Access constraint: $summary", summary.contains("wheelchair", true) || summary.contains("step-free", true))
                assertTrue("Unfinished task: $summary", summary.contains("Lee") && summary.contains("taxi", true))
                assertTrue("Travel date: $summary", summary.contains("November 12") || summary.contains("Nov 12"))
                assertEquals(messages, workspace.state.value.current!!.source!!.messages)
            }
        } finally { workspace.close(); SessionCache(context, profile).clear() }
    }

    @Test fun foregroundDisconnectLoadsFallbackAndPlusCreatesIntegratedLocalChat() = ui.withWindows {
        ui.guard()
        val runtime = BellpullRuntime.get(context)
        ui.main { runtime.disconnect() }
        val secure = SecureConnectionStore(context); secure.clear()
        val profile = secure.add("Offline fixture", "http://127.0.0.1:9", "fixture-only")
        secure.activate(profile.id)
        val cache = SessionCache(context, profile.id)
        cache.writeCapabilities("{\"features\":{\"session_chat_streaming\":true}}")
        cache.write(listOf(Session("source", title = "Saved remote chat", source = "api_server")), System.currentTimeMillis() / 1000.0)
        cache.writeHistory("source", listOf(Message(1, role = "user", content = "Cached source")))
        ui.main { runtime.localSettings.update(LocalFallbackConfiguration()) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                ui.await("automatic foreground model loading", 120000) {
                    ui.text("Local model ready · limited functionality") != null
                }
                val button = ui.find { it.contentDescription?.toString() == "New chat" }
                assertNotNull(button); ui.tap(button!!)
                ui.await("integrated local composer") { ui.text("Message Gemma on this phone") != null }
                val workspace = runtime.offlineWorkspace!!
                ui.await("local creation") { !workspace.state.value.busy }
                assertEquals(1, workspace.state.value.forks.size)
                workspace.draft("What is 2 plus 3? Answer with one number."); workspace.send()
                ui.await("integrated CPU reply", 120000) { !workspace.state.value.busy }
                assertNull(workspace.state.value.error)
                assertTrue(workspace.state.value.current!!.turns.last().text.contains("5"))
                ui.screenshot("fallback-integrated-chat")
                scene.recreate()
                ui.await("local chat survives recreation") { ui.text("Message Gemma on this phone") != null }
                scene.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                ui.await("model unloaded when app closes", 15000) { !runtime.localModel.state.value.ready }
            }
        } finally {
            ui.main { runtime.disconnect() }; cache.clear(); cache.close(); secure.clear()
        }
    }

    @Test fun configuredContextsLoadAndGenerate() = ui.withWindows {
        val lab = LocalLlmLab.get(context)
        ui.await("model scan") { lab.state.value.scanned && !lab.state.value.busy }
        val reports = JSONArray()
        // Keep an Activity visible so the app-lifecycle unload does not race this test.
        ActivityScenario.launch(ComponentActivity::class.java).use {
            for (size in LocalFallbackSettings.contexts) {
                val started = SystemClock.elapsedRealtime()
                val result = runBlocking { lab.offlineReply("context:$size", false, 8, size,
                    { listOf(LocalTurn("user", "What is 2 plus 3? Answer with one number.")) }, {}).await() }
                assertTrue(result.first.contains("5")); assertEquals(size, lab.state.value.contextTokens)
                reports.put(JSONObject(result.second).put("configured_context", size).put("wall_ms", SystemClock.elapsedRealtime() - started))
            }
        }
        File(context.getExternalFilesDir(null), "fallback-contexts.json").writeText(reports.toString(2))
    }

    @Test fun longForkWarnsCompactsResumesAndLongLocalHistoryCompactsAutomatically() = ui.withWindows {
        val profile = "fallback-compaction"
        SessionCache(context, profile).clear()
        LocalFallbackSettings(context).update(LocalFallbackConfiguration(contextTokens = 2048))
        val storage = OfflineStorage(context, profile)
        val source = OfflineSource("long-source", "Compaction fixture", listOf(Message(1, role = "user",
            content = "Project Blue Lantern meets in Cobalt Orchard at 3 pm. ".repeat(220))))
        storage.save(source)
        val workspace = OfflineWorkspace(context, profile) { null }
        fun idle(timeout: Long = 600000) = ui.await("compaction operation: ${workspace.state.value.status}", timeout) { !workspace.state.value.busy }
        val began = SystemClock.elapsedRealtime()
        try {
            idle()
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme {
                    OfflineScreen(workspace.state.collectAsState().value, null, "", workspace, {}, {}, {})
                } } }
                workspace.requestFork(source.id, source.title, download = false); idle()
                assertNull(workspace.state.value.error)
                assertTrue(workspace.state.value.forkReview!!.tokens > 2048)
                ui.await("long-context disclaimer") { ui.text("This will take a hot minute") != null }
                ui.screenshot("fallback-compaction-warning")
                ui.tapText("Compact local fork")
                ui.await("first saved chunk", 240000) { (workspace.state.value.current?.compaction?.chunks ?: 0) > 0 }
                workspace.stop(); idle(15000)
                val checkpoint = workspace.state.value.current!!.compaction!!
                assertTrue(checkpoint.offset > 0)
                val id = workspace.state.value.current!!.id
                assertEquals(checkpoint, storage.fork(id)!!.compaction)
                workspace.compactCurrent(); idle(); assertNull(workspace.state.value.error)
                val prepared = workspace.state.value.current!!
                assertTrue(prepared.sourcePrepared); assertNull(prepared.compaction)
                assertTrue(prepared.summary.contains("Cobalt Orchard"))
                assertEquals(source.messages, prepared.source!!.messages)
                // Restore a long local transcript without generating artificial filler on the phone.
                val turns = (1..8).map { OfflineTurn(role = if (it % 2 == 1) "user" else "assistant",
                    text = "Our venue is Cobalt Orchard. ".repeat(35)) }
                storage.save(prepared.copy(turns = turns))
                workspace.open(id); idle()
                workspace.draft("Which room is our venue? Answer only the room name.")
                workspace.send(); idle(); assertNull(workspace.state.value.error)
                val completed = workspace.state.value.current!!
                assertEquals(turns.size, completed.summarizedTurns)
                assertTrue(completed.turns.last().text.contains("Cobalt Orchard"))
                assertEquals(turns, completed.turns.take(turns.size))
                workspace.search("venue"); idle()
                assertTrue(workspace.state.value.searchResults.any { it.messageId < 0 && it.vector.isNotEmpty() })
                ui.screenshot("fallback-compacted-chat")
                File(context.getExternalFilesDir(null), "fallback-compaction.json").writeText(JSONObject()
                    .put("wall_ms", SystemClock.elapsedRealtime() - began).put("resumed_offset", checkpoint.offset)
                    .put("summary", completed.summary).put("automatic_compaction_turns", completed.summarizedTurns)
                    .put("answer", completed.turns.last().text).put("originals_preserved", true).toString(2))
            }
        } finally { workspace.close(); SessionCache(context, profile).clear() }
    }
}
