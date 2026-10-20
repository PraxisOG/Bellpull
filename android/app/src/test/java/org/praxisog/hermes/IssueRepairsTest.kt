package org.praxisog.hermes

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

class IssueRepairsTest {
    @Test fun historyPublicationAndLaterStatusCannotShowTheSavedReplyTwice() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = HermesClient("http://fixture.invalid", { "fixture" })
        val journal = object : RunJournal {
            override fun readRuns() = emptyList<RunSubmission>()
            override fun writeRun(value: RunSubmission) = true
        }
        val store = HermesStore(scope, client, durableRetention = 86400, journal = journal)
        try {
            store.openSession("one")
            val runs = store.runs!!
            val record = RunSubmission("request", "one", "A  repeated\nprompt", 1.0, 1, setOf(1, 2),
                runId = "run", status = "completed", completionOutput = "Final reply")
            runs.states["one"] = RunPresentation(record, TurnState.Finished("Final reply"))
            runs.onChanged("one")
            assertEquals(record.text, store.pendingUserText)
            val old = listOf(Message(1, role = "user", content = record.text), Message(2, role = "assistant", content = "Old reply"))
            runs.onHistory("one", old)
            assertEquals("Old matching prompt must not consume the current pending row", record.text, store.pendingUserText)
            val saved = old + listOf(Message(3, role = "user", content = "A repeated prompt"),
                Message(4, role = "assistant", content = "Final reply"))
            // This callback runs BEFORE the durable journal write finishes.
            runs.onHistory("one", saved)
            assertEquals(TurnState.Idle, store.turn)
            assertNull(store.pendingUserText)
            // Polls/approval changes can arrive while that journal write is suspended.
            runs.onChanged("one")
            assertEquals(TurnState.Idle, store.turn)
            assertNull(store.pendingUserText)
        } finally { store.close(); scope.cancel(); client.close() }
    }

    @Test fun nativeLoadFailureHasCooldownButLoadedEngineAndExplicitRetryAreImmediate() {
        var now = 0L
        var attempts = 0
        var broken = true
        val runtime = LocalTtsRuntime({}, loadCooldown = ModelLoadCooldown { now }) {
            attempts++
            check(!broken) { "fixture failure" }
            object : LocalTtsEngine {
                override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink) = 0
                override fun close() {}
            }
        }
        assertThrows(IllegalStateException::class.java) { runtime.withModel { _, _, _ -> } }
        repeat(5) {
            now += 1000
            assertThrows(IllegalStateException::class.java) { runtime.withModel { _, _, _ -> } }
        }
        assertEquals(1, attempts)
        now = 10_000
        assertThrows(IllegalStateException::class.java) { runtime.withModel { _, _, _ -> } }
        assertEquals(2, attempts)
        broken = false
        runtime.allowRetry()
        runtime.withModel { _, _, _ -> }
        repeat(10) { runtime.withModel { _, load, _ -> assertEquals(0.0, load, 0.0) } }
        assertEquals(3, attempts)
        runtime.unload()
    }
}
