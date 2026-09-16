package org.praxisog.hermes

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first instrumentation test, and it exists for a specific reason.
 *
 * Roborazzi renders composables outside an Activity, so Activity-level chrome is invisible to
 * it by construction — 28 screenshots all passed while the real app showed a redundant
 * platform action bar titled "Hermes" above the Compose content. That was only caught by
 * installing it on the phone and looking.
 *
 * This is the class of bug that needs a real Android runtime, which is what the Gradle
 * Managed Device is for.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityChromeTest {

    @Test
    fun activityDeclaresNoPlatformActionBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(
                    "MainActivity must use a NoActionBar theme: Compose draws this app's " +
                        "headers, so a platform action bar is duplicate chrome.",
                    activity.actionBar,
                )
            }
        }
    }

    @Test
    fun activityLaunchesAndComposesWithoutCrashing() {
        // A smoke test for the real runtime: the JVM tests never construct the Activity, so
        // nothing else here would catch a theme or manifest change that breaks startup.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNull("no action bar expected", activity.actionBar)
            }
        }
    }
}
