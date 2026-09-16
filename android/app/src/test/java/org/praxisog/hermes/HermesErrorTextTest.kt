package org.praxisog.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.praxisog.hermes.model.HermesError

/**
 * The screenshots prove the connection screen renders; they cannot prove the error mapping
 * survived a refactor. This does.
 */
class HermesErrorTextTest {

    @Test fun authFailureKeepsTheWordingTheAppShippedWith() {
        assertEquals(
            "Authentication failed. Check the Hermes API key.",
            HermesError.AuthFailed("Invalid gateway API key (API_SERVER_KEY)").toUserMessage(),
        )
    }

    @Test fun rateLimitUsesRetryAfterWhenTheServerSentOne() {
        assertEquals(
            "Hermes is rate limiting requests. Try again in 1s.",
            HermesError.RateLimited("Too many concurrent runs (max 10)", 1L).toUserMessage(),
        )
        assertEquals(
            "Hermes is rate limiting requests. Try again later.",
            HermesError.RateLimited("Too many concurrent runs (max 10)", null).toUserMessage(),
        )
    }

    @Test fun eachUnreachableKindGetsItsOwnWording() {
        val messages = HermesError.Unreachable.Kind.entries.map {
            HermesError.Unreachable(it, "detail").toUserMessage()
        }
        assertEquals("every kind should be distinguishable", messages.size, messages.toSet().size)
        assertTrue(
            HermesError.Unreachable(HermesError.Unreachable.Kind.DNS, "x").toUserMessage()
                .contains("Tailscale")
        )
    }

    @Test fun truncatedStreamDoesNotReadLikeAFailedRequest() {
        val text = HermesError.StreamTruncated(3, "EOFException").toUserMessage()
        // The turn may still be running server-side; saying it failed would be wrong.
        assertTrue("should say the turn may still be running", text.contains("may still be running"))
        assertFalse(text.contains("failed"))
    }

    @Test fun noUserFacingMessageLeaksOperatorDetail() {
        val errors = listOf(
            HermesError.AuthFailed("Invalid gateway API key (API_SERVER_KEY)"),
            HermesError.RateLimited("nope", 2L),
            HermesError.SessionNotFound("api_1_abc"),
            HermesError.UnexpectedRedirect(302, "http://example.invalid/moved"),
            HermesError.MalformedResponse("Unexpected JSON token at offset 0"),
            HermesError.StreamTruncated(1, "EOFException"),
            HermesError.Unreachable(HermesError.Unreachable.Kind.OTHER, "java.io.IOException"),
        )
        for (error in errors) {
            val text = error.toUserMessage()
            assertFalse("$error leaked an exception name: $text", text.contains("Exception"))
            assertFalse("$error leaked an env var name: $text", text.contains("API_SERVER_KEY"))
            assertTrue("$error produced empty text", text.isNotBlank())
            assertTrue("$error should end in a sentence", text.trimEnd().endsWith("."))
        }
    }

    @Test fun serverErrorAndUnparseableBothNameTheStatus() {
        assertTrue(HermesError.ServerError(500, "boom", null).toUserMessage().contains("500"))
        assertTrue(HermesError.UnparseableError(404, "404: Not Found").toUserMessage().contains("404"))
    }
}
