package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One transcript message. Note [id] is a server-assigned **integer**, not a string — unlike
 * session ids, which are strings like `api_1789554464_8413b4c7`.
 *
 * For `role == "tool"`, [content] is not prose: it is a JSON *string* holding the tool result.
 * Parse it with [ToolResult.parse] rather than displaying it raw.
 */
@Serializable
data class Message(
    val id: Int,
    @SerialName("session_id") val sessionId: String? = null,
    val role: String,
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val timestamp: Double? = null,
    @SerialName("token_count") val tokenCount: Int? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val reasoning: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
)

@Serializable
data class ToolCall(
    val id: String? = null,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("response_item_id") val responseItemId: String? = null,
    val type: String? = null,
    val function: ToolCallFunction? = null,
)

@Serializable
data class ToolCallFunction(
    val name: String? = null,
    /** Raw JSON string, as the provider emitted it. Not pre-parsed. */
    val arguments: String? = null,
)

/** `GET /api/sessions/{id}/messages`. Pagination is a nested object here. */
@Serializable
data class MessageHistory(
    val `object`: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val data: List<Message> = emptyList(),
    val pagination: Pagination? = null,
)

@Serializable
data class Pagination(
    val limit: Int = 0,
    val offset: Int = 0,
    val order: String? = null,
    val returned: Int = 0,
)
