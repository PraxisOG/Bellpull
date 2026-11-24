package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Xml
import com.caverock.androidsvg.SVG
import org.praxisog.hermes.model.*
import java.io.OutputStream
import java.io.StringWriter
import kotlin.math.*

internal enum class CanvasFormat(val extension: String, val mime: String) {
    SVG_FILE("svg", "image/svg+xml"), PNG("png", "image/png")
}

internal class CanvasExport(private val scene: CanvasContent) {
    private val nodes = scene.nodes.associateBy { it.id }
    private val x = nodes.values.minOfOrNull { it.x - 40 } ?: 0.0
    private val y = nodes.values.minOfOrNull { it.y - 40 } ?: 0.0
    private val w = nodes.values.maxOfOrNull { it.x + it.width + 40 - x } ?: 640.0
    private val h = nodes.values.maxOfOrNull { it.y + it.height + 40 - y } ?: 480.0
    private val scale = min(2.0, 2048 / max(w, h))
    private val width = ceil(w * scale).toInt().coerceIn(1, 2048)
    private val height = ceil(h * scale).toInt().coerceIn(1, 2048)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f }

    fun name(format: CanvasFormat) = scene.name.map { if (it.code < 32 || it == '/' || it == '\\') '-' else it }
        .joinToString("").take(140).trim().ifBlank { "Canvas" } + "." + format.extension

    fun write(format: CanvasFormat, output: OutputStream) {
        val source = svg()
        if (format == CanvasFormat.SVG_FILE) { output.write(source.toByteArray(Charsets.UTF_8)); return }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            SVG.getFromString(source).renderToCanvas(Canvas(bitmap))
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode this drawing." }
        } finally { bitmap.recycle() }
    }

    private fun xmlText(value: String): String = buildString {
        value.codePoints().forEach { cp ->
            if (cp == 9 || cp == 10 || cp == 13 || cp in 32..0xd7ff || cp in 0xe000..0xfffd || cp in 0x10000..0x10ffff)
                appendCodePoint(cp)
        }
    }

    private fun svg(): String {
        val out = StringWriter()
        val xml = Xml.newSerializer().apply { setOutput(out) }
        fun element(tag: String, vararg attributes: Pair<String, Any>, content: String? = null) {
            xml.startTag(null, tag)
            for ((key, value) in attributes) xml.attribute(null, key, value.toString())
            if (content != null) xml.text(xmlText(content))
            xml.endTag(null, tag)
        }
        fun label(text: String, cx: Double, cy: Double) {
            element("text", "x" to cx, "y" to cy, "fill" to "#362f2b", "font-family" to "sans-serif",
                "text-anchor" to "middle", "font-size" to 16, content = text)
        }
        xml.startTag(null, "svg").attribute(null, "xmlns", "http://www.w3.org/2000/svg")
            .attribute(null, "width", width.toString()).attribute(null, "height", height.toString())
            .attribute(null, "viewBox", "$x $y $w $h")
        element("title", content = scene.name)
        element("rect", "x" to x, "y" to y, "width" to w, "height" to h, "fill" to "#faf8f5")
        for (edge in scene.edges) {
            val a = nodes.getValue(edge.from); val b = nodes.getValue(edge.to)
            val (x1, y1) = anchor(a, b); val (x2, y2) = anchor(b, a)
            element("line", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "stroke" to "#84736c", "stroke-width" to 2)
            if (edge.arrow) {
                val angle = atan2(y2 - y1, x2 - x1)
                val left = "${x2 - 11 * cos(angle - .48)},${y2 - 11 * sin(angle - .48)}"
                val right = "${x2 - 11 * cos(angle + .48)},${y2 - 11 * sin(angle + .48)}"
                element("polygon", "points" to "$x2,$y2 $left $right", "fill" to "#84736c")
            }
            if (edge.label.isNotEmpty()) label(edge.label, (x1 + x2) / 2, (y1 + y2) / 2 - 8)
        }
        for (n in scene.nodes) {
            val cx = n.x + n.width / 2; val cy = n.y + n.height / 2
            val color = when (n.color) { "rose" -> "#e5cdd2"; "sage" -> "#d8e4db"; "blue" -> "#d5e2ec"; "white" -> "#ffffff"; else -> "#eee2ce" }
            if (n.shape == "ellipse")
                element("ellipse", "cx" to cx, "cy" to cy, "rx" to n.width / 2, "ry" to n.height / 2, "fill" to color, "stroke" to "#70635e")
            else if (n.shape != "text")
                element("rect", "x" to n.x, "y" to n.y, "width" to n.width, "height" to n.height,
                    "rx" to if (n.shape == "note") 2 else 10, "fill" to color, "stroke" to "#70635e")
            val rows = wrap(n)
            rows.forEachIndexed { i, text -> label(text, cx, cy - (rows.size - 1) * 10 + i * 20 + 5) }
        }
        xml.endTag(null, "svg"); xml.flush()
        return out.toString()
    }

    private fun wrap(node: CanvasNode): List<String> {
        val rows = mutableListOf<String>()
        val maxLines = max(1, ((node.height - 12) / 20).toInt())
        for (paragraph in node.label.split('\n')) {
            var left = paragraph
            do {
                if (rows.size == maxLines) { rows[rows.lastIndex] = rows.last().trimEnd() + "…"; return rows }
                val fit = paint.breakText(left, true, (node.width - 14).toFloat(), null)
                var count = if (fit >= left.length) left.length else left.lastIndexOf(' ', fit).takeIf { it > 0 } ?: fit
                if (left.isNotEmpty() && count == 0) count = Character.charCount(left.codePointAt(0))
                rows += left.take(count); left = left.drop(count).trimStart()
            } while (left.isNotEmpty())
        }
        return rows
    }

    private fun anchor(a: CanvasNode, b: CanvasNode): Pair<Double, Double> {
        val cx = a.x + a.width / 2; val cy = a.y + a.height / 2
        val dx = b.x + b.width / 2 - cx; val dy = b.y + b.height / 2 - cy
        val u = dx / (a.width / 2); val v = dy / (a.height / 2)
        val distance = if (a.shape == "ellipse") hypot(u, v) else max(abs(u), abs(v))
        return if (distance == 0.0) cx to cy else (cx + dx / distance) to (cy + dy / distance)
    }
}
