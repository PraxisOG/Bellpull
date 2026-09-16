package org.praxisog.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTextTest {
    private val now = 1_700_000_000.0

    @Test fun bucketsRelativeTimes() {
        assertEquals("just now", formatLastActive(now - 5, now))
        assertEquals("just now", formatLastActive(now - 59, now))
        assertEquals("1m ago", formatLastActive(now - 60, now))
        assertEquals("59m ago", formatLastActive(now - 3_599, now))
        assertEquals("1h ago", formatLastActive(now - 3_600, now))
        assertEquals("23h ago", formatLastActive(now - 86_399, now))
        assertEquals("1d ago", formatLastActive(now - 86_400, now))
        assertEquals("29d ago", formatLastActive(now - 2_591_999, now))
        assertEquals("1mo ago", formatLastActive(now - 2_592_000, now))
    }

    @Test fun missingTimestampIsNotZeroEpoch() {
        // A null last_active must not render as "55y ago" from an epoch-zero default.
        assertEquals("never", formatLastActive(null, now))
    }

    @Test fun clockSkewDoesNotRenderTheFuture() {
        // The phone's clock and the Hermes host's can disagree; "in 3m" would look broken.
        assertEquals("just now", formatLastActive(now + 180, now))
    }

    @Test fun groupsThousands() {
        assertEquals("0", groupDigits(0))
        assertEquals("872", groupDigits(872))
        assertEquals("1,240", groupDigits(1_240))
        assertEquals("66,858", groupDigits(66_858))
        assertEquals("1,234,567", groupDigits(1_234_567))
        assertEquals("-1,240", groupDigits(-1_240))
    }

    @Test fun pluralizesOnlyWhenItShould() {
        assertEquals("1 message", pluralize(1, "message"))
        assertEquals("0 messages", pluralize(0, "message"))
        assertEquals("12 messages", pluralize(12, "message"))
        assertEquals("1 tool call", pluralize(1, "tool call"))
        assertEquals("3 tool calls", pluralize(3, "tool call"))
    }

    @Test fun compactsModelIdsToTheirMeaningfulTail() {
        // The reported case: "accounts/firework…" identified the billing account, not the model.
        assertEquals(
            "deepseek-v4p1-flash",
            compactModelName("accounts/fireworks/models/deepseek-v4p1-flash"),
        )
        assertEquals("claude-opus-4.6", compactModelName("anthropic/claude-opus-4.6"))
        // A bare name has no path to strip.
        assertEquals("gpt-4o", compactModelName("gpt-4o"))
    }

    @Test fun compactModelNameToleratesOddInput() {
        assertEquals("", compactModelName(""))
        assertEquals("x", compactModelName("  x  "))
        // A trailing slash must not yield an empty label.
        assertEquals("models", compactModelName("accounts/fireworks/models/"))
        assertEquals("/", compactModelName("/"))
    }
}
