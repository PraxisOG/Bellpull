package org.praxisog.hermes

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the screenshot suite against being silently useless.
 *
 * Roborazzi's `verify` compares a recording against a golden. If the render produced nothing,
 * it compares blank against blank and passes — so a broken capture reads as a green suite.
 * Six screenshots in [HermesAppScreenshotTest] were blank across two commits for that reason:
 * adding a ComposeTestRule to the class broke the direct `captureRoboImage { }` calls, and
 * re-recording baked the blanks in as the new truth.
 *
 * A screenshot test that cannot fail is worse than no screenshot test, because it is counted.
 */
class ScreenshotsAreNotBlankTest {

    @Test
    fun noRecordedScreenshotIsBlank() {
        val dir = File("src/test/snapshots")
        assertTrue("snapshot directory not found at ${dir.absolutePath}", dir.isDirectory)

        val pngs = dir.listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty()
        assertTrue("expected recorded screenshots, found none", pngs.isNotEmpty())

        val blank = pngs.filter { file ->
            val image = ImageIO.read(file) ?: return@filter true
            val seen = HashSet<Int>()
            // A real screen has hundreds of distinct colours from text antialiasing alone.
            // Three is generous headroom for a legitimately flat surface.
            outer@ for (x in 0 until image.width step 2) {
                for (y in 0 until image.height step 2) {
                    seen += image.getRGB(x, y)
                    if (seen.size > 3) break@outer
                }
            }
            seen.size <= 3
        }

        assertTrue(
            "these screenshots rendered nothing: ${blank.map { it.name }}",
            blank.isEmpty(),
        )
    }
}
