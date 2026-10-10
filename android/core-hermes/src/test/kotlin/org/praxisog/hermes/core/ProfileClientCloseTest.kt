package org.praxisog.hermes.core

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.StreamEvent

class ProfileClientCloseTest {
    @Test fun aClosedProfileCannotStartAnotherRequest() {
        val client = MockHermes.client()
        client.close()
        assertTrue(client.capabilities() is HermesResult.Err)
        assertTrue(client.submitRun("session", "must not send", "fixture") is HermesResult.Err)
        assertTrue(client.streamTurn(MockHermes.FIXTURE_SESSION_ID, "must not send") {} is HermesResult.Err)
        assertTrue(client.runEvents("fixture") {} is HermesResult.Err)
        assertTrue("A different profile still works", MockHermes.client().capabilities() is HermesResult.Ok)
    }
    @Test fun closingAProfileCancelsItsActiveStream() {
        val client = MockHermes.client()
        var closed = false
        val result = client.streamTurn(MockHermes.FIXTURE_SESSION_ID, "fixture", onStart = { client.close(); closed = true }) {}
        assertTrue(closed)
        assertTrue(result is HermesResult.Err)
    }
}
