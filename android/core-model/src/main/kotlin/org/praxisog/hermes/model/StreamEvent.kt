package org.praxisog.hermes.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One SSE frame from a native session stream or durable `/v1/runs/{id}/events`.
 *
 * Event names and payload keys are from the recorded fixture plus the Gate 0c probes, which
 * added `tool.started` / `tool.completed` — see `docs/hermes-api.md` § Streaming. An
 * unrecognised event decodes to [Unknown] rather than throwing: the server may add events,
 * and a client that crashes on an unknown frame is worse than one that ignores it.
 */
sealed interface StreamEvent {
    val raw: JsonObject

    /** Monotonic per-run sequence number, present on every observed event. */
    val seq: Int? get() = raw["seq"]?.jsonPrimitive?.intOrNull

    data class RunStarted(override val raw: JsonObject, val runId: String?) : StreamEvent
    data class MessageStarted(override val raw: JsonObject, val messageId: String?) : StreamEvent

    /** A token of assistant text. Append [delta]; do not treat it as a whole message. */
    data class AssistantDelta(override val raw: JsonObject, val messageId: String?, val delta: String) : StreamEvent

    /** Completed interim prose that some native routes emit without text deltas. */
    data class AssistantCommentary(override val raw: JsonObject, val text: String, val alreadyStreamed: Boolean) : StreamEvent

    /**
     * Tool or reasoning progress. `tool_name` is `_thinking` for reasoning, which is why this
     * is not assumed to be a real tool call.
     */
    data class ToolProgress(override val raw: JsonObject, val toolName: String?, val delta: String?) : StreamEvent

    /** A tool call begins. [preview] and [args] are populated here and null on completion. */
    data class ToolStarted(override val raw: JsonObject, val toolName: String?, val preview: String?) : StreamEvent

    /**
     * A tool call ends. **Carries no result.** Verified live: on completion `preview` and
     * `args` are both null and no output field is present. The result is only in the message
     * history, as a `role == "tool"` message. A client that wants tool output must fetch it.
     */
    data class ToolCompleted(override val raw: JsonObject, val toolName: String?) : StreamEvent

    /**
     * The assistant message is final. [completed] / [partial] / [interrupted] are the flags
     * that distinguish a finished turn from a truncated one — render the difference.
     */
    data class AssistantCompleted(
        override val raw: JsonObject,
        val messageId: String?,
        val content: String?,
        val completed: Boolean,
        val partial: Boolean,
        val interrupted: Boolean,
    ) : StreamEvent

    /** Terminal success event, with usage. */
    data class RunCompleted(override val raw: JsonObject, val completed: Boolean) : StreamEvent

    /** Stream closed cleanly. */
    data class Done(override val raw: JsonObject) : StreamEvent

    data class Unknown(override val raw: JsonObject, val event: String) : StreamEvent

    /** True for the events that legitimately end a stream. Anything else means truncation. */
    val isTerminal: Boolean get() = this is Done || this is RunCompleted

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun JsonObject.str(key: String): String? =
            this[key]?.let { runCatching { it.jsonPrimitive.contentOrNullSafe() }.getOrNull() }

        private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
            if (this is kotlinx.serialization.json.JsonNull) null else content

        private fun JsonObject.bool(key: String): Boolean =
            this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false

        /**
         * Native stock run streams put the event name inside JSON and omit the optional
         * SSE `event:` line. Legacy streams name it explicitly; that name takes precedence.
         * Returns null only when [data] is not a JSON object at all.
         */
        fun parse(event: String?, data: String): StreamEvent? {
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
            val name = event?.takeIf { it.isNotEmpty() } ?: obj.str("event") ?: "message"
            return when (name) {
                "run.started" -> RunStarted(obj, obj.str("run_id"))
                "message.started" -> MessageStarted(
                    obj,
                    runCatching { obj["message"]?.jsonObject?.str("id") }.getOrNull(),
                )
                "assistant.delta", "message.delta" -> AssistantDelta(obj, obj.str("message_id"), obj.str("delta") ?: "")
                "message.interim", "assistant.commentary" -> AssistantCommentary(obj, obj.str("text") ?: "", obj.bool("already_streamed"))
                "reasoning.available", "reasoning.delta" -> ToolProgress(obj, "_thinking", obj.str("text"))
                "tool.progress" -> ToolProgress(obj, obj.str("tool_name"), obj.str("delta"))
                "tool.started" -> ToolStarted(obj, obj.str("tool_name") ?: obj.str("tool"), obj.str("preview"))
                "tool.completed" -> ToolCompleted(obj, obj.str("tool_name") ?: obj.str("tool"))
                "assistant.completed" -> AssistantCompleted(
                    obj, obj.str("message_id"), obj.str("content"),
                    completed = obj.bool("completed"),
                    partial = obj.bool("partial"),
                    interrupted = obj.bool("interrupted"),
                )
                "run.completed" -> RunCompleted(obj, obj.bool("completed"))
                "done" -> Done(obj)
                else -> Unknown(obj, name)
            }
        }
    }
}
