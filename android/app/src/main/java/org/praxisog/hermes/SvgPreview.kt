package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Xml
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGExternalFileResolver
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter

/** Static native rendering only. The original file is never modified. */
internal object SvgPreview {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val NS = "http://www.w3.org/2000/svg"
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private val tags = setOf("svg", "g", "defs", "symbol", "use", "path", "rect", "circle", "ellipse", "line",
        "polyline", "polygon", "text", "tspan", "title", "desc", "linearGradient", "radialGradient", "stop", "clipPath", "marker", "style")
    private val localId = Regex("#[A-Za-z_][A-Za-z0-9_.:-]*")
    private val url = Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
    private data class Node(val children: MutableList<Int> = mutableListOf(), val references: MutableList<String> = mutableListOf())

    internal fun sanitize(source: String): String {
        require(source.length <= MAX_BYTES) { "SVG is too large to preview." }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(StringReader(source))
        }
        val out = StringWriter()
        val writer = Xml.newSerializer().apply { setOutput(out); setPrefix("", NS); setPrefix("xlink", XLINK) }
        val nodes = mutableListOf<Node>()
        val ids = mutableMapOf<String, Int>()
        val stack = ArrayDeque<Int>()
        var skipped = 0
        var depth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> error("SVG document types and entities are not supported.")
                XmlPullParser.START_TAG -> {
                    depth++
                    require(depth <= 48 && nodes.size < 10000 && parser.attributeCount <= 64) { "SVG is too complex to preview." }
                    if (nodes.isEmpty() && skipped == 0) require(parser.name == "svg" && parser.namespace in setOf("", NS)) { "Not an SVG document." }
                    if (skipped > 0 || parser.name !in tags || parser.namespace !in setOf("", NS)) skipped++
                    else {
                        val node = Node()
                        val index = nodes.size
                        stack.lastOrNull()?.let { nodes[it].children += index }
                        nodes += node; stack.addLast(index)
                        writer.startTag(NS, parser.name)
                        for (i in 0 until parser.attributeCount) {
                            val name = parser.getAttributeName(i)
                            val namespace = parser.getAttributeNamespace(i)
                            val value = parser.getAttributeValue(i)
                            if (name.startsWith("on", ignoreCase = true) || namespace !in setOf("", XLINK) || '\\' in value || value.length > 131072) continue
                            if (name == "href") {
                                if (!localId.matches(value)) continue
                                node.references += value.drop(1)
                            }
                            val urls = url.findAll(value).toList()
                            if (value.contains("url", ignoreCase = true) && (urls.isEmpty() || urls.any {
                                    !localId.matches(it.groupValues[1].trim().trim('\'', '"'))
                                })) continue
                            node.references += urls.map { it.groupValues[1].trim().trim('\'', '"').drop(1) }
                            if (name == "style" && '@' in value) continue
                            if (name == "id") { require(value !in ids) { "Duplicate SVG identity." }; ids[value] = index }
                            writer.attribute(namespace, name, value)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skipped > 0) skipped--
                    else { writer.endTag(NS, parser.name); stack.removeLast() }
                    depth--
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (skipped == 0 && stack.isNotEmpty()) {
                    if (event == XmlPullParser.ENTITY_REF) require(parser.name in setOf("amp", "lt", "gt", "quot", "apos") ||
                        parser.name.orEmpty().matches(Regex("#(?:[0-9]+|x[0-9a-fA-F]+)"))) { "SVG entities are not supported." }
                    val text = parser.text.orEmpty()
                    // CSS imports, escapes and resource references are omitted. Inline
                    // local paint references above remain available to diagrams.
                    writer.text(text)
                }
            }
            event = parser.nextToken()
        }
        require(nodes.isNotEmpty() && depth == 0)
        writer.flush()
        // Remove stylesheet rules containing resource syntax before AndroidSVG parses
        // CSS. A deny-all resolver is a second boundary, including processing instructions.
        val sanitized = out.toString().replace(Regex("<style(?:\\s[^>]*)?>[\\s\\S]*?</style>")) { match ->
            if (match.value.contains(Regex("[@\\\\]|url\\s*\\(", RegexOption.IGNORE_CASE))) "" else match.value
        }
        val visiting = mutableSetOf<Int>()
        val cost = mutableMapOf<Int, Int>()
        fun expanded(index: Int, level: Int): Int {
            require(level <= 64 && visiting.add(index)) { "Cyclic SVG references are not supported." }
            cost[index]?.let { visiting.remove(index); return it }
            val node = nodes[index]
            var total = 1
            for (child in node.children + node.references.mapNotNull(ids::get)) {
                total += expanded(child, level + 1)
                require(total <= 100000) { "SVG reference expansion is too large." }
            }
            visiting.remove(index); cost[index] = total
            return total
        }
        expanded(0, 0)
        return sanitized
    }

    @Synchronized
    fun render(file: File, maxSide: Int = 2048): Bitmap {
        require(file.length() in 1..MAX_BYTES.toLong()) { "SVG is too large to preview." }
        require(maxSide in 1..2048)
        val source = file.inputStream().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (bytes.size() <= MAX_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size()))
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
        require(source.size <= MAX_BYTES)
        SVG.setInternalEntitiesEnabled(false)
        SVG.registerExternalFileResolver(object : SVGExternalFileResolver() {}) // all resource methods return null
        val svg = SVG.getFromString(sanitize(source.toString(Charsets.UTF_8)))
        val viewBox = svg.documentViewBox
        val width = svg.documentWidth.takeIf { it.isFinite() && it > 0 } ?: viewBox?.width() ?: 512f
        val height = svg.documentHeight.takeIf { it.isFinite() && it > 0 } ?: viewBox?.height() ?: 512f
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        // SVG dimensions describe coordinates, not available pixels. Render small
        // diagrams at the preview's bounded resolution so a Fold's large display
        // and zoom controls do not merely enlarge a tiny bitmap.
        val longest = maxOf(width, height).toDouble()
        val bitmap = Bitmap.createBitmap((width.toDouble() / longest * maxSide).toInt().coerceIn(1, maxSide),
            (height.toDouble() / longest * maxSide).toInt().coerceIn(1, maxSide), Bitmap.Config.ARGB_8888)
        if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
        svg.setDocumentWidth(bitmap.width.toFloat()); svg.setDocumentHeight(bitmap.height.toFloat())
        try { svg.renderToCanvas(Canvas(bitmap)) } catch (error: Exception) { bitmap.recycle(); throw error }
        return bitmap
    }
}
