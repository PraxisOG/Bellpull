package org.praxisog.hermes

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SystemSettingsTest {
    @Test fun assistantSelectionFallsBackToDefaultAppsWhenTheSpecificPageIsMissing() {
        val seen = mutableListOf<String>()
        assertTrue(launchSystemSettings(assistantSettingsIntents()) { intent ->
            seen += intent.action!!
            if (seen.size == 1) throw ActivityNotFoundException()
        })
        assertEquals(listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), seen)
        assertFalse(seen.contains("android.app.role.action.REQUEST_ROLE"))
    }
    @Test fun rejectedSystemSettingsReportFailureInsteadOfPretendingTheyOpened() {
        var calls = 0
        assertFalse(launchSystemSettings(assistantSettingsIntents()) { calls++; throw SecurityException() })
        assertEquals(2, calls)
    }
    @Test fun notificationSettingsOnlyTargetThisAppAndFallBackToItsDetails() {
        val attempted = mutableListOf<Intent>()
        assertTrue(launchSystemSettings(notificationSettingsIntents("fixture.app")) {
            attempted += it
            if (attempted.size == 1) throw ActivityNotFoundException()
        })
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, attempted[0].action)
        assertEquals("fixture.app", attempted[0].getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempted[1].action)
        assertEquals("package:fixture.app", attempted[1].dataString)
    }
}
