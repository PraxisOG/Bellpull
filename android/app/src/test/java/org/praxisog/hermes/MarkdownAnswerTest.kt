package org.praxisog.hermes

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.commonmark.node.FencedCodeBlock

class MarkdownAnswerTest {
    @Test fun inlineFormattingPreservesReadableTextAndRestrictsLinks() {
        val text = markdownInline(parseAnswer("**Bold** and *italic* with `code`, [site](https://example.com) and [bad](javascript:alert)."), Color.Blue, Color.Gray)
        assertEquals("Bold and italic with code, site and bad.", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
        assertTrue(text.spanStyles.size >= 3)
    }
    @Test fun anUnclosedFenceRemainsACodeBlockWhileStreaming() {
        val node = parseAnswer("```kotlin\nval answer = 42\n").firstChild as FencedCodeBlock
        assertEquals("val answer = 42\n", node.literal)
        assertEquals("kotlin", node.info)
    }
    @Test fun rawHtmlIsTextAndImagesDoNotLoadResources() {
        val text = markdownInline(parseAnswer("<script>bad()</script>\n\n![A sketch](https://example.com/private.png)"), Color.Blue, Color.Gray)
        assertEquals("A sketch", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }
}
