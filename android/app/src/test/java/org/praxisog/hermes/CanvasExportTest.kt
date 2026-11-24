package org.praxisog.hermes

import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CanvasExportTest {
    @Test fun serializesLabelsAsTextAndBoundsRasterForLargeScenes() {
        val n = CanvasNode("a", "note", "<drawing> & text", -20000.0, 0.0, 100.0, 200.0, "rose")
        val b = n.copy(id = "b", x = 20000.0, shape = "ellipse")
        val export = CanvasExport(CanvasContent("dir/scene", listOf(n, b), listOf(CanvasEdge("line", "a", "b", "next", true))))
        assertEquals("dir-scene.svg", export.name(CanvasFormat.SVG_FILE))
        val out = ByteArrayOutputStream()
        export.write(CanvasFormat.SVG_FILE, out)
        val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(out.toByteArray().inputStream())
        assertEquals(0, svg.getElementsByTagName("drawing").length)
        assertEquals(1, svg.getElementsByTagName("polygon").length)
        assertEquals(1, svg.getElementsByTagName("ellipse").length)
        assertTrue(svg.documentElement.textContent.contains("<drawing>"))
        out.reset()
        export.write(CanvasFormat.PNG, out)
        val bytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try { assertEquals(2048, bitmap.width); assertTrue(bitmap.height in 1..100) } finally { bitmap.recycle() }
    }
}
