package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Hermes session. Field names and types are taken from live 0.21.1 responses recorded in
 * `mock-hermes/fixtures/` — see `docs/hermes-api.md`. Every field the server may omit has a
 * default, and the decoder ignores unknown keys, so a server-side field addition widens the
 * contract instead of breaking the client.
 *
 * [lastActive] is present in list responses but absent from the `POST /api/sessions` reply,
 * which is why it defaults rather than being required.
 */
@Serializable
data class Session(
    val id: String,
    val source: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val model: String? = null,
    val title: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("tool_call_count") val toolCallCount: Int = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long = 0,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double? = null,
    @SerialName("actual_cost_usd") val actualCostUsd: Double? = null,
    @SerialName("api_call_count") val apiCallCount: Int = 0,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    val preview: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val hidden: Boolean = false,
    @SerialName("has_system_prompt") val hasSystemPrompt: Boolean = false,
    @SerialName("has_model_config") val hasModelConfig: Boolean = false,
)

/**
 * `GET /api/sessions`. Pagination is flat here — [limit], [offset], [hasMore] sit on the
 * envelope. Message history uses a nested object instead; see [MessageHistory].
 */
@Serializable
data class SessionList(
    val `object`: String? = null,
    val data: List<Session> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * `POST /api/sessions`. The created session arrives under `session`, **not** `data` — a third
 * envelope shape. Verified live 2026-09-16.
 */
@Serializable
data class SessionCreated(
    val `object`: String? = null,
    val session: Session,
)
