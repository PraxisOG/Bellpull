package org.praxisog.hermes.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Historical evidence from one completed native tool call in one chat. */
data class MemoryStatus(
    val ownerAvailable: Boolean,
    val visibleRecords: Long?,
    val unfinishedMessages: Long?,
    val workerEnabled: Boolean?,
    val provider: String?,
    val observedAtMillis: Long?,
    val contextCreatedAtMillis: Long?,
) {
    companion object {
        fun parse(toolName: String?, raw: String?): MemoryStatus? {
            if (toolName != "memory_status" || raw == null) return null
            val result = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (_: SerializationException) { null } ?: return null
            if (result["error"]?.let { it != JsonNull } == true ||
                result["status"]?.let { it != JsonNull && (it as? JsonPrimitive)?.content != "ok" } == true) return null
            val access = result["access"] as? JsonObject ?: return null
            val available = when ((access["owner_memory"] as? JsonPrimitive)?.content) {
                "available" -> true
                "unavailable" -> false
                else -> return null
            }
            val evidence = (result["bellpull_memory"] as? JsonObject)?.takeIf {
                it.number("version")?.longOrNull == 1L &&
                    (it["provider"] as? JsonPrimitive)?.content == "memory-subsystem"
            }
            val observed = evidence?.seconds("observed_at")
            val created = evidence?.seconds("context_created_at")?.takeIf { observed != null && it <= observed }
            return MemoryStatus(
                available,
                result.number("records")?.longOrNull?.takeIf { it >= 0 },
                result.number("unfinished_messages")?.longOrNull?.takeIf { it >= 0 },
                (result["worker_enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
                if (observed != null) "memory-subsystem" else null,
                observed?.let { (it * 1000).toLong() },
                created?.let { (it * 1000).toLong() },
            )
        }

        /** Match a result to its preceding call in the selected profile's chat transcript. */
        fun latest(sessionId: String, messages: List<Message>): MemoryStatus? {
            val calls = mutableMapOf<String, String?>()
            var latest: MemoryStatus? = null
            for (message in messages) {
                if (message.sessionId != null && message.sessionId != sessionId) continue
                when (message.role) {
                    "assistant" -> message.toolCalls.orEmpty().forEach { call ->
                        listOfNotNull(call.id, call.callId).forEach { id -> calls[id] = call.function?.name }
                    }
                    "tool" -> {
                        val name = calls.remove(message.toolCallId) ?: continue
                        if (name == "memory_status" && (message.toolName == null || message.toolName == name)) {
                            latest = parse(name, message.content)
                        }
                    }
                }
            }
            return latest
        }

        private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }
        private fun JsonObject.seconds(key: String) = number(key)?.doubleOrNull?.takeIf { it > 0 && it < 253402300800.0 }
    }
}
