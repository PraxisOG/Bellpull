package org.praxisog.hermes

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.core.text.util.LinkifyCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser

private val answerParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

internal fun parseAnswer(text: String): Node = answerParser.parse(transcriptText(text))

/** Android's detector includes sentence punctuation; keep balanced URL path brackets. */
private fun literalLinkEnd(text: String, start: Int, end: Int): Int {
    val balance = intArrayOf(0, 0, 0)
    for (index in start until end) when (text[index]) {
        '(' -> balance[0]++
        ')' -> balance[0]--
        '[' -> balance[1]++
        ']' -> balance[1]--
        '{' -> balance[2]++
        '}' -> balance[2]--
    }
    var stop = end
    // Some Android releases trim every trailing closing bracket, including a
    // legitimate balanced path suffix such as Wikipedia's "Thing_(part)".
    while (stop < text.length) {
        val bracket = ")]}".indexOf(text[stop])
        if (bracket < 0 || balance[bracket] <= 0) break
        balance[bracket]--
        stop++
    }
    while (stop > start) {
        val last = text[stop - 1]
        val bracket = ")]}".indexOf(last)
        if (last in ".,:;!?" || (bracket >= 0 && balance[bracket] < 0)) {
            if (bracket >= 0) balance[bracket]++
            stop--
        } else break
    }
    return stop
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

/** Native text only: Markdown cannot execute HTML or load remote images. The source is
 * retained by the message actions/selection dialog, including during partial streams. */
@Composable
internal fun MarkdownAnswer(text: String, modifier: Modifier = Modifier) {
    val document = remember(text) { parseAnswer(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarkdownBlocks(document.children())
    }
}

@Composable
private fun MarkdownBlocks(nodes: List<Node>, depth: Int = 0) {
    nodes.forEachIndexed { index, node -> key(index) {
        when (node) {
            is org.commonmark.node.Paragraph -> MarkdownTextBlock(node)
            is Heading -> MarkdownTextBlock(node, when (node.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }.copy(fontWeight = FontWeight.SemiBold))
            is FencedCodeBlock -> CodeBlock(node.literal, node.info.substringBefore(' '))
            is IndentedCodeBlock -> CodeBlock(node.literal, "")
            is BulletList, is OrderedList -> {
                val start = (node as? OrderedList)?.markerStartNumber ?: 1
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children().forEachIndexed { itemIndex, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (node is OrderedList) "${start + itemIndex}." else "•",
                                style = MaterialTheme.typography.bodyLarge)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (depth < 16) MarkdownBlocks(item.children(), depth + 1)
                                else MarkdownTextBlock(item)
                            }
                        }
                    }
                }
            }
            is BlockQuote -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(2.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.outline))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (depth < 16) MarkdownBlocks(node.children(), depth + 1) else MarkdownTextBlock(node)
                }
            }
            is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 2.dp))
            is TableBlock -> MarkdownTable(node)
            is HtmlBlock -> Text(node.literal, style = MaterialTheme.typography.bodyLarge)
            else -> MarkdownTextBlock(node)
        }
    } }
}

@Composable
private fun MarkdownTextBlock(node: Node, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    Text(markdownInline(node, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHigh),
        style = style)
}

/** Link annotations use Compose's native accessibility/opening behavior. Unsupported
 * schemes remain readable text. Images are represented by their alt text, never fetched. */
internal fun markdownInline(node: Node, linkColor: androidx.compose.ui.graphics.Color,
    codeColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    fun appendLinkedText(text: String) {
        val start = length
        append(text)
        if (!text.contains("://") && !text.contains("www.", ignoreCase = true)) return
        // Use AndroidX's existing URL detector; code, HTML, images and explicit link
        // labels never enter this path. Restrict literals to http(s) and www URLs.
        val linked = SpannableString(text)
        LinkifyCompat.addLinks(linked, Linkify.WEB_URLS)
        linked.getSpans(0, linked.length, URLSpan::class.java).forEach { span ->
            val from = linked.getSpanStart(span)
            val originalEnd = linked.getSpanEnd(span)
            val to = literalLinkEnd(text, from, originalEnd)
            val literal = text.substring(from, to)
            if ((literal.startsWith("https://", true) || literal.startsWith("http://", true) ||
                    literal.startsWith("www.", true)) &&
                (from == 0 || text[from - 1] !in ":/@")) {
                val url = if (to > originalEnd) span.url + text.substring(originalEnd, to)
                    else span.url.dropLast(originalEnd - to)
                addLink(LinkAnnotation.Url(url, linkStyle), start + from, start + to)
            }
        }
    }
    fun render(current: Node, depth: Int = 0, autolink: Boolean = true) {
        if (depth > 64) return
        fun children(linkify: Boolean = autolink) { current.children().forEach { render(it, depth + 1, linkify) } }
        when (current) {
            is MarkdownText -> if (autolink) appendLinkedText(current.literal) else append(current.literal)
            is SoftLineBreak -> append("\n")
            is HardLineBreak -> append("\n")
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor)) { append(current.literal) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
            is Link -> {
                val scheme = runCatching { java.net.URI(current.destination).scheme?.lowercase() }.getOrNull()
                if (scheme in setOf("https", "http", "mailto")) withLink(LinkAnnotation.Url(current.destination, linkStyle)) { children(false) }
                else children(false)
            }
            is Image -> children(false)
            is HtmlInline -> append(current.literal)
            else -> children()
        }
    }
    render(node)
}

@Composable
private fun CodeBlock(code: String, language: String) {
    val copy = rememberMessageCopy()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { "Code" }, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { copy(code) }, modifier = Modifier.testTag("copy-code")) { Text("Copy code") }
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("code-scroll").padding(10.dp)) {
                Text(code.removeSuffix("\n"), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false)
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    val rows = table.children().flatMap { it.children() }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("table-scroll")) {
        Column {
            rows.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.children().filterIsInstance<TableCell>().forEach { cell ->
                        Surface(color = if (cell.isHeader) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(168.dp).fillMaxHeight()) {
                            Text(markdownInline(cell, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHigh),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (cell.isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = when (cell.alignment) {
                                    TableCell.Alignment.CENTER -> TextAlign.Center
                                    TableCell.Alignment.RIGHT -> TextAlign.End
                                    else -> TextAlign.Start
                                }, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}
