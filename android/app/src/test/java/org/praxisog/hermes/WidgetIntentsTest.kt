package org.praxisog.hermes

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun newChatIntentTargetsThisAppsActivityExplicitly() {
        val intent = WidgetIntents.newChat(context)
        // Explicit component: a widget tap must never be resolvable by another app.
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
    }

    @Test fun newChatIntentIsRecognised() {
        assertTrue(WidgetIntents.isNewChat(WidgetIntents.newChat(context)))
    }

    @Test fun anOrdinaryLaunchIsNotANewChatRequest() {
        // Tapping the app icon must not silently create a session.
        assertFalse(WidgetIntents.isNewChat(Intent(Intent.ACTION_MAIN)))
        assertFalse(WidgetIntents.isNewChat(null))
    }

    @Test fun theIntentReusesAnExistingTask() {
        // Without this, tapping the widget while the app is open stacks a second copy.
        val flags = WidgetIntents.newChat(context).flags
        assertTrue("expected SINGLE_TOP", flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue("expected CLEAR_TOP", flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
}
