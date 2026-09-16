package org.praxisog.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesEndpointTest {
    @Test fun acceptsTailnetAddress() {
        assertEquals("http://100.101.42.3:8642", HermesEndpoint.parse("http://100.101.42.3:8642/").getOrThrow())
    }

    @Test fun rejectsPublicPlainHttp() {
        assertTrue(HermesEndpoint.parse("http://example.com:8642").isFailure)
    }

    @Test fun rejectsEmbeddedCredentialsAndPath() {
        assertTrue(HermesEndpoint.parse("https://user:pass@example.com").isFailure)
        assertTrue(HermesEndpoint.parse("https://example.com/api").isFailure)
    }
}
