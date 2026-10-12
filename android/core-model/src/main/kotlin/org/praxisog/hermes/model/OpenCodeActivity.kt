package org.praxisog.hermes.model

import kotlinx.serialization.json.*

/**
 * Phase 6 prototype, not a Hermes wire contract or an enabled integration.
 * A future authenticated host adapter must establish all identities explicitly.
 * Process ID, OpenCode session ID and Hermes run/session IDs are different namespaces.
 */
data class OpenCodeWorkBinding(
    val profileId: String,
    val hermesSessionId: String,
    val hermesRunId: String,
    val openCodeSessionId: String,
    val processId: String? = null,
    /** Display metadata only; never a client-supplied file-read authority. */
    val workspace: String? = null,
) {
    init {
        require(listOf(profileId, hermesSessionId, hermesRunId, openCodeSessionId).all { it.isNotBlank() && it.length <= 256 })
        require(processId == null || processId.isNotBlank() && processId.length <= 256)
        require(workspace == null || workspace.length <= 4096)
    }
    override fun toString() = "OpenCodeWorkBinding(identities=redacted)"
}

enum class CodingActivityKind { STEP_STARTED, STEP_FINISHED, TEXT, REASONING, TOOL, ERROR }
enum class CodingToolOutcome { COMPLETED, ERROR }

/** Plain text only: no executable terminal, HTML, approval action or inferred file access. */
data class CodingActivityRow(
    val key: String,
    val timestampMs: Long,
    val kind: CodingActivityKind,
    val label: String,
    val detail: String,
    val command: String? = null,
    val toolOutcome: CodingToolOutcome? = null,
    val truncated: Boolean = false,
) {
    override fun toString() = "CodingActivityRow(kind=$kind, content=redacted)"
}

sealed interface OpenCodeLine {
    data class Activity(val row: CodingActivityRow) : OpenCodeLine
    data object OtherSession : OpenCodeLine
    data object Unsupported : OpenCodeLine
    data object Invalid : OpenCodeLine
}

/**
 * Source-checked against OpenCode v1.19.0 (733b2c6), `run --format json`.
 * This CLI envelope is NOT the OpenCode SSE API. It emits completed tool/text
 * parts, not token deltas, replay cursors, pending questions or run completion.
 */
object OpenCodeCliFormat {
    const val sourceVersion = "v1.19.0"
    const val maxLineChars = 131072
    private const val maxDetailChars = 32768
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(line: String, binding: OpenCodeWorkBinding): OpenCodeLine {
        if (line.length > maxLineChars || !boundedNesting(line)) return OpenCodeLine.Invalid
        val root = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return OpenCodeLine.Invalid
        val session = root.string("sessionID") ?: return OpenCodeLine.Invalid
        if (session != binding.openCodeSessionId) return OpenCodeLine.OtherSession
        val timestamp = root.number("timestamp")?.takeIf { it >= 0 } ?: return OpenCodeLine.Invalid
        val type = root.string("type") ?: return OpenCodeLine.Invalid
        if (type == "error") {
            val error = root["error"] as? JsonObject ?: return OpenCodeLine.Invalid
            val name = error.string("name")?.takeIf { it.length <= 256 } ?: return OpenCodeLine.Invalid
            val detail = (error["data"] as? JsonObject)?.string("message").orEmpty()
            // CLI errors have no part ID: timestamp is diagnostic identity, never a replay cursor.
            return OpenCodeLine.Activity(CodingActivityRow("error:$timestamp:$name", timestamp,
                CodingActivityKind.ERROR, name, clipped(detail), truncated = detail.length > maxDetailChars))
        }
        val kind = when (type) {
            "step_start" -> CodingActivityKind.STEP_STARTED
            "step_finish" -> CodingActivityKind.STEP_FINISHED
            "text" -> CodingActivityKind.TEXT
            "reasoning" -> CodingActivityKind.REASONING
            "tool_use" -> CodingActivityKind.TOOL
            else -> return OpenCodeLine.Unsupported
        }
        val part = root["part"] as? JsonObject ?: return OpenCodeLine.Invalid
        if (part.string("sessionID") != session) return OpenCodeLine.Invalid
        val id = part.string("id")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return OpenCodeLine.Invalid
        val message = part.string("messageID")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return OpenCodeLine.Invalid
        val expected = when (kind) {
            CodingActivityKind.STEP_STARTED -> "step-start"
            CodingActivityKind.STEP_FINISHED -> "step-finish"
            CodingActivityKind.TOOL -> "tool"
            else -> type
        }
        if (part.string("type") != expected) return OpenCodeLine.Invalid
        var outcome: CodingToolOutcome? = null
        var command: String? = null
        val label: String
        val detail: String
        when (kind) {
            CodingActivityKind.TOOL -> {
                label = part.string("tool")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return OpenCodeLine.Invalid
                val state = part["state"] as? JsonObject ?: return OpenCodeLine.Invalid
                outcome = when (state.string("status")) {
                    "completed" -> CodingToolOutcome.COMPLETED
                    "error" -> CodingToolOutcome.ERROR
                    else -> return OpenCodeLine.Unsupported // CLI does not emit pending/running tools.
                }
                detail = state.string(if (outcome == CodingToolOutcome.ERROR) "error" else "output")
                    ?: return OpenCodeLine.Invalid
                if (label == "bash") command = (state["input"] as? JsonObject)?.string("command")
            }
            CodingActivityKind.TEXT, CodingActivityKind.REASONING -> {
                label = if (kind == CodingActivityKind.TEXT) "OpenCode response" else "OpenCode reasoning"
                detail = part.string("text") ?: return OpenCodeLine.Invalid
            }
            else -> {
                label = if (kind == CodingActivityKind.STEP_STARTED) "OpenCode step" else "OpenCode step finished"
                detail = part.string("reason").orEmpty()
            }
        }
        return OpenCodeLine.Activity(CodingActivityRow("${message.length}:$message$id", timestamp, kind, label,
            clipped(detail), command?.let(::clipped), outcome,
            detail.length > maxDetailChars || (command?.length ?: 0) > maxDetailChars))
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun clipped(value: String): String = value.take(maxDetailChars).let {
        if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
    }
    private fun boundedNesting(line: String): Boolean {
        var depth = 0; var quoted = false; var escaped = false
        for (c in line) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return depth == 0 && !quoted
    }
}

/**
 * Bounded, per-binding prototype transcript. No admission, control or network calls.
 * Receipt order is retained; existing part IDs replace rows. Replay suppression is
 * only within the retained window. A durable host cursor/snapshot is still required.
 */
class OpenCodeCliTimeline(val binding: OpenCodeWorkBinding, private val capacity: Int = 200) {
    init { require(capacity in 1..1000) }
    private val buffer = StringBuilder()
    private var droppingLine = false
    private val retained = LinkedHashMap<String, CodingActivityRow>()
    val rows: List<CodingActivityRow> get() = retained.values.toList()
    var invalidLines = 0L; private set
    var ignoredLines = 0L; private set
    var evictedRows = 0L; private set
    var connectionInterrupted = false; private set
    /** Always false: a step-finish/error/EOF never proves the child or Hermes run settled. */
    val authoritativeCompletion: Boolean get() = false

    fun append(text: String) {
        for (c in text) {
            if (c == '\n') {
                if (!droppingLine) consume(buffer.toString().removeSuffix("\r"))
                buffer.setLength(0); droppingLine = false
            } else if (!droppingLine) {
                if (buffer.length >= OpenCodeCliFormat.maxLineChars) {
                    buffer.setLength(0); droppingLine = true; invalidLines++
                } else buffer.append(c)
            }
        }
    }
    fun connectionLost() {
        // A partial last line cannot be joined to an unrelated reconnect/replay response.
        if (buffer.isNotEmpty()) invalidLines++
        buffer.setLength(0); droppingLine = false; connectionInterrupted = true
    }
    private fun consume(line: String) {
        if (line.isBlank()) return
        when (val result = OpenCodeCliFormat.decode(line, binding)) {
            is OpenCodeLine.Activity -> {
                retained[result.row.key] = result.row
                while (retained.size > capacity) { retained.remove(retained.keys.first()); evictedRows++ }
            }
            OpenCodeLine.Invalid -> invalidLines++
            OpenCodeLine.OtherSession, OpenCodeLine.Unsupported -> ignoredLines++
        }
    }
}
