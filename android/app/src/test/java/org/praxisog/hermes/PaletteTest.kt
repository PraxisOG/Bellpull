package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test

class PaletteTest {
    @Test fun presetsAndExtremeCustomColorsKeepReadableText() {
        for (dark in listOf(false, true)) for (palette in Palette.entries) {
            for (accent in listOf("000000", "FFFFFF", "FFFF00", "FF0000", "00FF00", "0000FF", "59091E", "381B18")) {
                val colors = paletteColors(palette, dark, accent)
                for ((text, background) in listOf(colors.onSurface to colors.surface,
                    colors.primary to colors.surface, colors.primary to colors.primaryContainer,
                    colors.onPrimary to colors.primary, colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSurfaceVariant to colors.surfaceContainerLow)) {
                    assertTrue("Unreadable $palette dark=$dark accent=$accent: ${contrastRatio(text, background)}",
                        contrastRatio(text, background) >= 4.5f)
                }
            }
        }
    }
    @Test fun customInputAcceptsHexAndRejectsMalformedValues() {
        assertEquals("AABBCC", normalizedAccent(" #aAbBcC "))
        for (invalid in listOf("", "FFF", "1234567", "GGHHII", "00000000")) assertNull(normalizedAccent(invalid))
    }
}
