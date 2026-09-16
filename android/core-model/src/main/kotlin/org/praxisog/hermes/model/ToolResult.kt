package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The decoded `content` of a `role == "tool"` message.
 *
 * Observed live on 2026-09-16 (see `mock-hermes/fixtures/terminal-tool-results.json`):
 * ```json
 * {"output": "hermes", "exit_code": 0, "error": null}
 * {"output": "", "exit_code": 0, "error": null,
 *  "approval": "Command was flagged (recursive delete) and auto-approved by smart approval."}
 * ```
 *
 * [approval] and [status] are why this type exists rather than the raw string being displayed.
 * Because the `api_server` platform is excluded from Hermes' approval contexts, this client
 * never receives a pending approval; a flagged command is instead auto-approved (leaving an
 * [approval] audit note) or refused outright with [status] `"blocked"`. Both must be visible.
 * See `docs/hermes-api.md` § Gate 0c.
 */
@Serializable
data class ToolResult(
    val output: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val error: String? = null,
    /**
     * Audit note attached when a guard fired but the command still ran. Present means
     * "something flagged this" — surface it; a silently auto-approved `rm -rf` is the thing
     * we do not want.
     */
    val approval: String? = null,
    /**
     * `"blocked"` when the command was refused and never executed, `"error"` on an internal
     * failure, `"pending_approval"` in gateway ask mode. Absent on a normal result.
     *
     * `"blocked"` is reachable on this transport: flagged commands that smart approval does
     * not auto-approve fall to `approvals.unattended_mode`, which defaults to `deny`. It is
     * a deliberate refusal by the server, not a client or network failure, and must not be
     * rendered as a generic error.
     */
    val status: String? = null,
) {
    /** True when the server refused to run the command. Nothing executed. */
    val isBlocked: Boolean get() = status == "blocked"

    /** True when a guard flagged the command but it ran anyway. */
    val wasFlaggedAndAllowed: Boolean get() = approval != null && !isBlocked

    /** True when the command ran and reported success. */
    val succeeded: Boolean get() = status == null && error == null && exitCode == 0

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Decode a tool message's `content`. Returns null when the content is absent or is
         * not a tool-result object — some tools return prose, and the fixture history has a
         * tool message whose content is a plain string. A null here means "show it as text",
         * never "the call failed".
         */
        fun parse(content: String?): ToolResult? {
            val trimmed = content?.trim() ?: return null
            if (!trimmed.startsWith("{")) return null
            return runCatching { json.decodeFromString<ToolResult>(trimmed) }.getOrNull()
        }
    }
}
