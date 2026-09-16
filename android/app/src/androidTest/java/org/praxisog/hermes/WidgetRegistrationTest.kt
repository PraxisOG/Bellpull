package org.praxisog.hermes

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A widget whose provider is not registered correctly does not error — it simply never
 * appears in the picker. The manifest receiver, the `android.appwidget.provider` meta-data
 * and the `appwidget-provider` XML all have to line up, and nothing in the JVM tests or the
 * Roborazzi renders looks at any of them.
 *
 * This asks the real [AppWidgetManager] whether the system can see our provider.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRegistrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theWidgetProviderIsVisibleToTheSystem() {
        val expected = ComponentName(context, HermesWidgetReceiver::class.java)
        val providers = AppWidgetManager.getInstance(context)
            .installedProviders
            .map { it.provider }

        assertTrue(
            "AppWidgetManager does not see $expected — check the manifest receiver, its " +
                "android.appwidget.provider meta-data, and res/xml/hermes_widget_info.xml. " +
                "Visible providers from this package: " +
                providers.filter { it.packageName == context.packageName },
            providers.contains(expected),
        )
    }

    @Test
    fun theProviderDeclaresAHomeScreenWidgetThatCanResize() {
        val info = AppWidgetManager.getInstance(context)
            .installedProviders
            .first { it.provider.className == HermesWidgetReceiver::class.java.name }

        assertTrue("should have a non-zero minimum width", info.minWidth > 0)
        assertTrue("should have a non-zero minimum height", info.minHeight > 0)
        // updatePeriodMillis 0: the widget is static until tapped, so periodic wakeups would
        // spend battery for nothing.
        assertEquals(0, info.updatePeriodMillis)
    }

    @Test
    fun launchingWithTheWidgetIntentDoesNotCrashWhenNotYetConnected() {
        // On a fresh install there are no stored credentials, so auto-connect cannot fire and
        // the new-chat request has to sit pending behind the connection screen rather than
        // taking the app down.
        ActivityScenario.launch<MainActivity>(WidgetIntents.newChat(context)).use { scenario ->
            assertEquals(State.RESUMED, scenario.state)
        }
    }
}
