package org.praxisog.hermes

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.praxisog.hermes.model.*

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class OfflineTest {
    @Test fun tokenizerMatchesOfficialBertUncasedExamples() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tokenizer = WordPiece(context.assets.open("search/vocab.txt").bufferedReader().use { it.readLines() })
        assertArrayEquals(longArrayOf(101, 7592, 1010, 2088, 999, 102), tokenizer.tokens("Hello, WORLD!"))
        // Oracle: pinned Hugging Face tokenizer.json via the reference tokenizers runtime, padding removed.
        assertArrayEquals(longArrayOf(101, 7668, 15743, 13746, 102), tokenizer.tokens("Café naïve résumé"))
        assertArrayEquals(longArrayOf(101, 100, 100, 1989, 1745, 100, 1986, 102), tokenizer.tokens("你好，世界！"))
        assertArrayEquals(longArrayOf(101, 103, 102), tokenizer.tokens("[MASK]"))
        assertArrayEquals(tokenizer.tokens("cafe"), tokenizer.tokens("Café"))
        assertArrayEquals(tokenizer.tokens("tabs and line breaks"), tokenizer.tokens("tabs\tand\nline breaks"))
        assertEquals(256, tokenizer.tokens("word ".repeat(400)).size)
        assertEquals(102, tokenizer.tokens("word ".repeat(400)).last())
    }
    @Test fun chunkingPreservesBoundariesAndRetrievesDistantFacts() {
        val source = OfflineSource("s", "Long chat", listOf(Message(1, role = "user", content = "ordinary text ".repeat(1000)),
            Message(200, role = "assistant", content = "The relay code word is Cobalt Orchard.")))
        val chunks = OfflineSearch.chunks(source)
        assertTrue(chunks.size > 20)
        assertEquals(200, OfflineSearch.search(chunks, "relay code word").first().messageId)
        assertTrue(chunks.all { it.id.startsWith("m${it.messageId}:") })
        val allText = chunks.filter { it.messageId == 1 }.mapIndexed { index, c -> if (index == 0) c.text else c.text.drop(40) }.joinToString("")
        assertEquals(source.messages[0].content, allText)
    }
    @Test fun thinkingAndToolRequestsAreParsedWithoutExecutingOtherTools() {
        assertEquals(OfflinePrompt.Output("", "Let me reason"), OfflinePrompt.output("<|channel>thought\nLet me reason"))
        assertEquals(OfflinePrompt.Output("5", "2+3"), OfflinePrompt.output("<|channel>thought\n2+3\n<channel|>5"))
        assertEquals("search_chat", OfflinePrompt.tool("{\"tool\":\"search_chat\",\"query\":\"the meeting\"}")?.name)
        assertNull(OfflinePrompt.tool("{\"tool\":\"shell\",\"query\":\"rm -rf\"}"))
        assertNull(OfflinePrompt.tool("{\"tool\":\"search_chat\",\"query\":\"meeting\",\"source_id\":\"someone-else\"}"))
        assertNull(OfflinePrompt.tool("An example: {\"tool\":\"search_chat\",\"query\":\"meeting\"}"))
    }
    @Test fun optionalPhoneContextYieldsBudgetToTheActualQuestion() {
        val fork = OfflineFork(thinking = false, turns = listOf(OfflineTurn(role = "user", text = "Where is the meeting?")))
        val small = OfflinePrompt.build(fork, emptyList(), null, false, phoneContext = "PHONE CONTEXT: west gate") { it.sumOf { t -> t.text.length } }
        assertTrue(small.last().text.contains("west gate"))
        val large = OfflinePrompt.build(fork, emptyList(), null, false, phoneContext = "PHONE CONTEXT: "+"data ".repeat(2000)) { it.sumOf { t -> t.text.length } }
        assertTrue(large.last().text.endsWith("Where is the meeting?"))
        assertFalse(large.any { it.text.contains("PHONE CONTEXT:") })
    }

    @Test fun contextIsBoundedAndThinkingAndIncompleteRepliesNeverEnterHistory() {
        val fork = OfflineFork(thinking = true, memory = OfflineMemory(text = "memory ".repeat(1000)),
            turns = listOf(OfflineTurn(role = "user", text = "old ".repeat(2000)),
                OfflineTurn(role = "assistant", text = "incomplete poison", thought = "private thought", status = "interrupted"),
                OfflineTurn(role = "user", text = "Where is the meeting?")))
        val prompt = OfflinePrompt.build(fork, emptyList(), null, true) { turns -> turns.sumOf { it.text.length } / 3 }
        assertTrue(prompt.sumOf { it.text.length } / 3 <= 1280)
        assertTrue(prompt.last().text.endsWith("Where is the meeting?"))
        assertFalse(prompt.any { it.text.contains("private thought") || it.text.contains("incomplete poison") })
        assertEquals("system", prompt.first().role)
    }
}
