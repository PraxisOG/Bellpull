package org.praxisog.hermes.core

import okio.BufferedSource
import org.praxisog.hermes.model.StreamEvent

/**
 * Minimal SSE frame reader. Reads line by line off a live [BufferedSource] so events surface
 * as they arrive rather than after the body completes.
 *
 * Deliberately not a full SSE implementation: Hermes sends only `event:` and `data:` lines
 * and never uses `id:`, `retry:`, or multi-line data. Comment lines (`:`) are skipped as
 * keep-alives. If Hermes ever starts sending multi-line data payloads this must grow a
 * concatenation step — the check is that [StreamEvent.parse] stops returning non-null.
 */
internal class SseReader(private val source: BufferedSource) {

    /**
     * Invoke [onEvent] for each complete frame until the stream ends. Returns the number of
     * events delivered. A partial trailing frame is dropped: half an event is not an event.
     */
    fun read(onEvent: (StreamEvent) -> Unit): Int {
        var event: String? = null
        val data = StringBuilder()
        var delivered = 0

        fun flush() {
            val name = event
            if (name != null && data.isNotEmpty()) {
                StreamEvent.parse(name, data.toString())?.let {
                    onEvent(it)
                    delivered++
                }
            }
            event = null
            data.setLength(0)
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit // keep-alive comment
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
            }
        }
        // A stream that ends without a blank line still has one pending frame.
        flush()
        return delivered
    }
}
