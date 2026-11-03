package org.praxisog.hermes

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Artifact
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class SvgPreviewTest {
    private val diagram = """<svg xmlns="http://www.w3.org/2000/svg" width="480" height="240" viewBox="0 0 480 240">
        <rect width="480" height="240" fill="#faf4ef"/>
        <defs><marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M0 0 L10 5 L0 10 Z" fill="#59091e"/></marker></defs>
        <rect x="25" y="75" width="150" height="80" rx="12" fill="#59091e"/>
        <rect x="300" y="75" width="150" height="80" rx="12" fill="#59091e"/>
        <path d="M180 115 H290" stroke="#59091e" stroke-width="4" marker-end="url(#arrow)"/>
        <text x="100" y="122" text-anchor="middle" fill="white" font-size="20">Phone</text>
        <text x="375" y="122" text-anchor="middle" fill="white" font-size="20">Hermes</text>
        </svg>"""
    private fun file(source: String) = File.createTempFile("svg-preview", ".svg").apply { writeText(source); deleteOnExit() }
    @Test fun rendersStaticDiagramWithoutChangingOriginalBytes() {
        val source = file(diagram)
        val bitmap = SvgPreview.render(source)
        assertEquals(2048, bitmap.width); assertEquals(1024, bitmap.height)
        assertEquals(Color.rgb(89, 9, 30), bitmap.getPixel(150, 384))
        assertEquals(diagram, source.readText())
        val withoutViewBox = SvgPreview.render(file(diagram.replace(" viewBox=\"0 0 480 240\"", "")))
        assertEquals(Color.rgb(89, 9, 30), withoutViewBox.getPixel(150, 384))
    }
    @Test fun stripsScriptsForeignContentExternalImagesAndStylesheets() {
        val source = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" onload="evil()">
            <?xml-stylesheet href="https://external.invalid/style.css"?>
            <script>evil()</script><foreignObject><div>Private content</div></foreignObject>
            <image href="file:///private"/><use href="https://external.invalid/image.svg#x"/>
            <style>@import 'https://external.invalid/style.css'; rect {fill:red}</style>
            <rect width="20" height="20" fill="blue" style="fill:url(https://external.invalid/fill.svg)"/>
            </svg>"""
        val safe = SvgPreview.sanitize(source)
        for (forbidden in listOf("onload", "script", "foreignObject", "image", "external.invalid", "file:///", "evil")) assertFalse(safe.contains(forbidden))
        assertEquals(Color.BLUE, SvgPreview.render(file(source)).getPixel(10, 10))
    }
    @Test fun rejectsDocumentTypesAndEntityExpansion() {
        assertThrows(Exception::class.java) { SvgPreview.sanitize("""<!DOCTYPE svg [<!ENTITY secret SYSTEM "file:///private">]><svg xmlns="http://www.w3.org/2000/svg"><text>&secret;</text></svg>""") }
        assertThrows(Exception::class.java) { SvgPreview.sanitize("""<!DOCTYPE svg SYSTEM "https://external.invalid/evil.dtd"><svg/>""") }
    }
    @Test fun rejectsRecursiveUseAndClippingBeforeRendering() {
        assertThrows(Exception::class.java) { SvgPreview.render(file("""<svg xmlns="http://www.w3.org/2000/svg"><g id="cycle"><use href="#cycle"/></g></svg>""")) }
        assertThrows(Exception::class.java) { SvgPreview.render(file("""<svg xmlns="http://www.w3.org/2000/svg"><clipPath id="cycle"><rect width="10" height="10" clip-path="url(#cycle)"/></clipPath></svg>""")) }
    }
    @Test fun dimensionsAndSourceSizeAreBounded() {
        val bitmap = SvgPreview.render(file("""<svg xmlns="http://www.w3.org/2000/svg" width="1000000" height="500000"><rect width="1000000" height="500000" fill="red"/></svg>"""), 768)
        assertEquals(768, bitmap.width); assertEquals(384, bitmap.height)
        val tiny = SvgPreview.render(file("""<svg xmlns="http://www.w3.org/2000/svg" width="1e-38" height="1e-38"/>"""), 768)
        assertEquals(768, tiny.width); assertEquals(768, tiny.height)
        assertThrows(Exception::class.java) { SvgPreview.render(file(" ".repeat(SvgPreview.MAX_BYTES + 1))) }
    }
    private fun screenshot() {
        val bitmap = SvgPreview.render(file(diagram))
        val item = Artifact("a".repeat(32), "chat", "diagram.svg", "image/svg+xml", diagram.length.toLong(), "b".repeat(64), 1.0, "agent")
        captureRoboImage { HermesTheme { Surface(Modifier.fillMaxSize()) {
            ArtifactPreviewContent(item, bitmap.asImageBitmap(), 0, 1, false, null, true,
                onPage = {}, onOpen = {}, onSave = {}, onShare = {}, onDock = null, onClose = {})
        } } }
    }
    @Test fun svgPreviewCover() = screenshot()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun svgPreviewUnfoldedDark() = screenshot()
}
