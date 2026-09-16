package org.praxisog.hermes.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.StreamEvent
import java.io.File

/**
 * Opt-in smoke test against a real Hermes instance. Skipped unless both are set:
 *
 * ```
 * HERMES_SMOKE_URL=https://host.tailnet.ts.net
 * HERMES_SMOKE_KEY_FILE=~/.config/hermes-android/dev-api-key
 * ```
 *
 * It is opt-in because the normal suite must stay hermetic — fixtures only, no homelab, no
 * inference cost, no network. This exists because "it decodes the recorded fixture" and "it
 * works against the real server" are different claims, and Slice 2 has to make both.
 *
 * The key is read from a file and never logged, never asserted on, and never written into a
 * failure message.
 */
class HermesLiveSmokeTest {

    private fun liveClient(): HermesClient? {
        val url = System.getenv("HERMES_SMOKE_URL")?.trim()?.ifEmpty { null } ?: return null
        val keyFile = System.getenv("HERMES_SMOKE_KEY_FILE")?.trim()?.ifEmpty { null } ?: return null
        val file = File(keyFile.replaceFirst("~", System.getProperty("user.home")))
        if (!file.isFile) return null
        val key = file.readText().trim()
        if (key.isEmpty()) return null
        return HermesClient(url, { key }, memoryScopeKey = "hermes-android-smoke")
    }

    @Test fun streamsARealReplyFromARealSession() {
        val client = liveClient()
        assumeTrue("set HERMES_SMOKE_URL and HERMES_SMOKE_KEY_FILE to run the live smoke test", client != null)
        requireNotNull(client)

        val caps = client.capabilities()
        assertTrue(
            "capabilities failed: ${(caps as? HermesResult.Err)?.error?.summary}",
            caps is HermesResult.Ok && caps.value.sessionChatStreaming,
        )

        val created = client.createSession(title = "core-hermes live smoke")
        val session = (created as? HermesResult.Ok)?.value
            ?: throw AssertionError("createSession failed: ${(created as HermesResult.Err).error.summary}")

        val deltas = StringBuilder()
        val events = mutableListOf<StreamEvent>()
        val result = client.streamTurn(session.id, "Reply with exactly: smoke ok") { event ->
            events += event
            if (event is StreamEvent.AssistantDelta) deltas.append(event.delta)
        }

        if (result is HermesResult.Err) throw AssertionError("stream failed: ${result.error.summary}")
        assertTrue("expected a terminal event", events.any { it.isTerminal })
        assertTrue("expected a non-empty assistant reply", deltas.isNotBlank())

        // A provider failure still emits run.completed, so a completed run with no content is
        // a distinct condition the client has to notice rather than render as an empty reply.
        val completed = events.filterIsInstance<StreamEvent.AssistantCompleted>().lastOrNull()
        assertTrue("assistant.completed should report a finished turn", completed?.completed == true)

        // Leave the transcript readable rather than deleting it, and prove history round-trips.
        val history = client.messages(session.id)
        val messages = (history as? HermesResult.Ok)?.value
            ?: throw AssertionError("messages failed: ${(history as HermesResult.Err).error.summary}")
        assertTrue("history should contain the turn", messages.data.size >= 2)
        println("[live smoke] session=${session.id} events=${events.size} reply=${deltas.length} chars")
    }
}
