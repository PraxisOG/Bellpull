package org.praxisog.hermes

import org.praxisog.hermes.model.HermesError
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.Session

/**
 * Deterministic sample data for the screenshot tests. Shapes and wording mirror
 * `mock-hermes/fixtures/`, including the Gate 0c tool results, so the screenshots exercise
 * what the server actually sends rather than something invented for a pretty picture.
 */
internal object UiFixtures {
    /** Fixed clock so relative timestamps render identically on every run. */
    const val NOW = 1_700_000_000.0

    val sessions = listOf(
        Session(
            id = "api_1_alpha",
            source = "api_server",
            model = "deepseek-v4p1-flash",
            title = "Tailscale routing for the Fold",
            preview = "Checked the serve config and the tailnet ACLs; the phone resolves the name fine.",
            startedAt = NOW - 900,
            lastActive = NOW - 240,
            messageCount = 12,
            toolCallCount = 3,
            inputTokens = 66_858,
            outputTokens = 872,
            cacheReadTokens = 61_004,
            cacheWriteTokens = 1_240,
            reasoningTokens = 96,
            apiCallCount = 5,
            estimatedCostUsd = 0.0412,
            hasSystemPrompt = true,
            hasModelConfig = true,
        ),
        Session(
            id = "api_2_beta",
            source = "api_server",
            title = "Widget cold-start timings",
            preview = "Two taps from the launcher, measured at 410ms cold.",
            startedAt = NOW - 86_400 * 2,
            lastActive = NOW - 7_200,
            messageCount = 4,
            toolCallCount = 0,
        ),
        Session(
            id = "api_3_gamma",
            source = "api_server",
            title = null,
            preview = null,
            startedAt = NOW - 86_400 * 9,
            lastActive = NOW - 86_400 * 9,
            messageCount = 1,
            toolCallCount = 0,
        ),
    )

    /**
     * A transcript with both tool-result states from Gate 0c: a flagged-and-auto-approved
     * recursive delete, and a refusal.
     */
    val history = listOf(
        Message(id = 1, role = "user", content = "Clean up the stale probe directory.", timestamp = NOW - 300),
        Message(id = 2, role = "assistant", content = "I'll remove it.", timestamp = NOW - 299),
        Message(
            id = 3, role = "tool", toolName = "terminal", toolCallId = "call_1",
            content = """{"output": "", "exit_code": 0, "error": null, "approval": "Command was flagged (recursive delete) and auto-approved by smart approval."}""",
            timestamp = NOW - 298,
        ),
        Message(id = 4, role = "assistant", content = "Removed. Now checking the mount.", timestamp = NOW - 297),
        Message(
            id = 5, role = "tool", toolName = "terminal", toolCallId = "call_2",
            content = """{"output": "", "exit_code": -1, "error": "Command denied: writes outside the safe root.", "status": "blocked"}""",
            timestamp = NOW - 296,
        ),
        Message(
            id = 6, role = "tool", toolName = "terminal", toolCallId = "call_3",
            content = """{"output": "hermes", "exit_code": 0, "error": null}""",
            timestamp = NOW - 295,
        ),
        Message(id = 7, role = "assistant", content = "The safe-root write was refused, so I left it alone.", timestamp = NOW - 294),
    )

    val apps = listOf(
        LaunchableApp("Organic Maps", "org.organicmaps", "org.organicmaps.Main"),
        LaunchableApp("Aegis Authenticator", "com.beemdevelopment.aegis", "com.beemdevelopment.aegis.Main"),
        LaunchableApp("Weather", "org.breezyweather", "org.breezyweather.Main"),
    )

    val truncation = HermesError.StreamTruncated(14, "EOFException")
    val authFailure = HermesError.AuthFailed("Invalid gateway API key (API_SERVER_KEY)")
}
