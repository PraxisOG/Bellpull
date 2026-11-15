package org.praxisog.hermes

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.commonmark.node.FencedCodeBlock
import androidx.compose.ui.text.LinkAnnotation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownAnswerTest {
    @Test fun bareUrlsKeepPunctuationOutsideTheLink() {
        val source = "See https://example.com/a?b=1&c=2, and (www.example.org/path)."
        val text = markdownInline(parseAnswer(source), Color.Blue, Color.Gray)
        assertEquals(source, text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://example.com/a?b=1&c=2", "http://www.example.org/path"),
            links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals(listOf("https://example.com/a?b=1&c=2", "www.example.org/path"),
            links.map { text.text.substring(it.start, it.end) })
    }
    @Test fun autolinksDoNotOverrideExplicitLinksOrActivateCodeImagesOrBlockedSchemes() {
        val text = markdownInline(parseAnswer("[https://label.example](https://destination.example) " +
            "`https://code.example` ![www.image.example](https://image.example) " +
            "[https://blocked.example](javascript:alert) javascript:https://evil.example " +
            "ftp://www.files.example"), Color.Blue, Color.Gray)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://destination.example"), links.map { (it.item as LinkAnnotation.Url).url })
    }
    @Test fun balancedPathBracketsAndFormattedUrlsArePreserved() {
        val text = markdownInline(parseAnswer("(https://example.com/wiki/Thing_(part)). " +
            "**https://example.org/test** and `www.example.org`"), Color.Blue, Color.Gray)
        assertEquals(listOf("https://example.com/wiki/Thing_(part)", "https://example.org/test"),
            text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url })
    }
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
