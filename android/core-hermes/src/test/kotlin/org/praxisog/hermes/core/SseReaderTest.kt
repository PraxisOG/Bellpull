package org.praxisog.hermes.core

import okio.Buffer
import okio.Pipe
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.StreamEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SseReaderTest {
    @Test fun stockJsonNamedFramesReachTheReaderBeforeTheStreamCloses() {
        val pipe = Pipe(4096)
        val events = CopyOnWriteArrayList<StreamEvent>()
        val arrived = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val source = pipe.source.buffer()
        val sink = pipe.sink.buffer()
        val reader = executor.submit<Int> { SseReader(source).read { events += it; arrived.countDown() } }
        try {
            sink.writeUtf8(": open\n\nid: 0\ndata: {\"event\":\"reasoning.delta\",\"run_id\":\"run_fixture\",\"text\":\"First live update.\",\"seq\":0}\n\n").flush()
            assertTrue("Reasoning must arrive while the stream remains open", arrived.await(3, TimeUnit.SECONDS))
            assertEquals("First live update.", (events.single() as StreamEvent.ToolProgress).delta)
            assertEquals(0, events.single().seq)
            assertFalse(reader.isDone)
            sink.writeUtf8("id: 1\ndata: {\"event\":\"run.completed\",\"completed\":true,\"seq\":1}\n\n").close()
            assertEquals(2, reader.get(3, TimeUnit.SECONDS))
            assertTrue(events.last().isTerminal)
        } finally {
            sink.close(); source.close(); executor.shutdownNow()
        }
    }

    @Test fun namedFramesTakePrecedenceAndNamesResetBetweenFrames() {
        val body = Buffer().writeUtf8("""
            : keepalive
            event: message.delta
            data: {"event":"reasoning.delta","delta":"Legacy prose","seq":0}

            id: 1
            data: {"event":"tool.started",
            data: "tool":"read_file","seq":1}

            data: {"event":"future.event","seq":2}

            data: {"seq":3}

        """.trimIndent() + "\n")
        val events = mutableListOf<StreamEvent>()
        assertEquals(4, SseReader(body).read(events::add))
        assertEquals("Legacy prose", (events[0] as StreamEvent.AssistantDelta).delta)
        assertEquals("read_file", (events[1] as StreamEvent.ToolStarted).toolName)
        assertEquals("future.event", (events[2] as StreamEvent.Unknown).event)
        assertEquals("message", (events[3] as StreamEvent.Unknown).event)
        assertEquals(listOf(0, 1, 2, 3), events.map { it.seq })
    }
}
