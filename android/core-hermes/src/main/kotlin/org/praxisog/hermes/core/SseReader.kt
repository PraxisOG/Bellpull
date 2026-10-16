package org.praxisog.hermes.core

import okio.BufferedSource
import org.praxisog.hermes.model.StreamEvent

/**
 * Minimal SSE frame reader. Reads line by line off a live [BufferedSource] so events surface
 * as they arrive rather than after the body completes.
 *
 * Accepts both explicitly named legacy frames and stock run frames containing only
 * `id:` / `data:` lines, whose JSON carries the event name and sequence number. Comments
 * are keep-alives; the JSON sequence drives replay. Multi-line data is joined with newlines.
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
            if (data.isNotEmpty()) {
                StreamEvent.parse(event, data.toString())?.let {
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
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        // A stream that ends without a blank line still has one pending frame.
        flush()
        return delivered
    }
}
