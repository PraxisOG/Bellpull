package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test

class AboutSettingsTest {
    @Test fun oldVersionsNeedUpdateAndNumericOrderingHandlesMultipleDigits() {
        assertTrue(pluginVersionStatus("0.2.2", true).startsWith("Update needed"))
        assertTrue(pluginVersionStatus("0.3.0-preview.1", true).startsWith("Update needed"))
        for (version in listOf("0.3.0", "0.10.0", "1.0.0")) assertTrue(pluginVersionStatus(version, true).startsWith("Meets"))
    }
    @Test fun missingUncheckedAndMalformedVersionsNeverClaimCompatibility() {
        assertTrue(pluginVersionStatus(null, false).startsWith("Not checked"))
        assertTrue(pluginVersionStatus(null, true).startsWith("Plugin version not reported"))
        assertTrue(pluginVersionStatus("unknown", true).startsWith("Unknown"))
        assertTrue(pluginVersionStatus("0.03.0", true).startsWith("Unknown"))
    }
}
