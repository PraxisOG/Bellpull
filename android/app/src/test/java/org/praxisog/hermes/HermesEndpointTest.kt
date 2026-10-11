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

    @Test fun preservesValidatedNativeProfilePrefixAndNormalizesTrailingSlash() {
        for (url in listOf("https://HOST/p/work", "https://HOST/p/work/"))
            assertEquals("https://host/p/work", HermesEndpoint.parse(url).getOrThrow())
        assertEquals("https://host", HermesEndpoint.parse("https://host/").getOrThrow())
        assertEquals("http://localhost:8642/p/work-2_a", HermesEndpoint.parse("http://localhost:8642/p/work-2_a/").getOrThrow())
    }

    @Test fun rejectsUnsafeOrMalformedProfilePathsWithoutDroppingThem() {
        for (path in listOf("/p/", "/p/Work", "/p/../work", "/p/work/..", "/p/work/api", "/p/work//",
            "//p/work", "/p/%77ork", "/p/work%2fother", "/p/work%5cother", "/p/.work", "/p/" + "a".repeat(65),
            "/p/work?account=root", "/p/work#fragment"))
            assertTrue(path, HermesEndpoint.parse("https://host$path").isFailure)
        assertTrue(HermesEndpoint.parse("http://public.example/p/work").isFailure)
        assertTrue(HermesEndpoint.parse("https://user:pass@host/p/work").isFailure)
    }
}
