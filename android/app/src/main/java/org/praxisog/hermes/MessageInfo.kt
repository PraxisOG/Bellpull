package org.praxisog.hermes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.RunSubmission
import java.util.Locale

internal data class MessageInfo(
    val durationSeconds: Double? = null,
    val estimatedDuration: Boolean = false,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
) {
    val available: Boolean get() = durationSeconds != null || inputTokens != null || outputTokens != null
}

private fun interval(start: Double?, end: Double?): Double? {
    if (start == null || end == null || !start.isFinite() || !end.isFinite() || start <= 0 || end <= start) return null
    return (end - start).takeIf { it.isFinite() }
}

internal fun usageCounter(usage: JsonObject?, key: String): Long? {
    val value = usage?.get(key) as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.longOrNull?.takeIf { it >= 0 }
}

/** A run is attributed to one uniquely reconciled final row, never all rows in its turn. */
internal fun messageRunInfo(messages: List<Message>, records: List<RunSubmission>): Map<Int, MessageInfo> =
    records.filter { it.status == "completed" }.mapNotNull { record ->
        matchedCompletion(record, messages)?.let { reply -> reply.id to MessageInfo(
            durationSeconds = interval(record.serverCreatedAt, record.serverCompletedAt),
            inputTokens = usageCounter(record.completionUsage, "input_tokens"),
            outputTokens = usageCounter(record.completionUsage, "output_tokens"),
        ) }
    }.groupBy { it.first }.mapNotNull { (id, matches) ->
        matches.singleOrNull()?.let { id to it.second }
    }.toMap()

internal fun messageInfo(message: Message, messages: List<Message>, runInfo: Map<Int, MessageInfo>): MessageInfo {
    if (message.role != "assistant") return MessageInfo()
    val known = runInfo[message.id] ?: MessageInfo()
    if (known.durationSeconds != null) return known
    val index = messages.indexOfFirst { it.id == message.id }
    if (index < 0 || !message.toolCalls.isNullOrEmpty()) return known
    val before = messages.take(index)
    val user = before.lastOrNull { it.role == "user" } ?: return known
    val after = messages.drop(index + 1).takeWhile { it.role != "user" }
    // Intermediate model/tool envelopes don't represent the end of the whole turn.
    if (after.any { it.role == "assistant" || it.role == "tool" }) return known
    val estimate = interval(user.timestamp, message.timestamp) ?: return known
    return known.copy(durationSeconds = estimate, estimatedDuration = true)
}

internal fun formatWorkingDuration(seconds: Double): String = when {
    seconds < 1 -> "<1 s"
    seconds < 60 -> String.format(Locale.ROOT, "%.1f s", seconds)
    seconds < 3600 -> "${seconds.toLong() / 60} min ${seconds.toLong() % 60} s"
    else -> "${seconds.toLong() / 3600} h ${seconds.toLong() % 3600 / 60} min"
}
