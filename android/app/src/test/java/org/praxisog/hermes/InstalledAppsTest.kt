package org.praxisog.hermes

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the real `ACTION_MAIN` + `CATEGORY_LAUNCHER` query against Robolectric's package
 * manager, so the enumeration path is covered without needing a device with apps installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstalledAppsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun installLauncherApp(pkg: String, activity: String, label: String) {
        val shadow = shadowOf(context.packageManager)
        val component = ComponentName(pkg, activity)
        shadow.addActivityIfNotPresent(component)
        shadow.addIntentFilterForActivity(
            component,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
        )
        shadow.getInternalMutablePackageInfo(pkg).applicationInfo?.let { it.name = label }
    }

    @Test fun findsLauncherActivities() {
        installLauncherApp("com.example.alpha", "com.example.alpha.Main", "Alpha")
        installLauncherApp("com.example.beta", "com.example.beta.Main", "Beta")

        val apps = InstalledApps.load(context)
        val packages = apps.map { it.packageName }
        assertTrue("expected alpha, got $packages", packages.contains("com.example.alpha"))
        assertTrue("expected beta, got $packages", packages.contains("com.example.beta"))
    }

    @Test fun excludesOurOwnLauncherEntry() {
        // Hermes appearing in Hermes' own app search is noise.
        installLauncherApp(context.packageName, "${context.packageName}.MainActivity", "Hermes")
        val apps = InstalledApps.load(context)
        assertTrue(
            "our own package must be filtered out",
            apps.none { it.packageName == context.packageName },
        )
    }

    @Test fun resultsAreSortedAndDeduplicated() {
        installLauncherApp("com.example.zulu", "com.example.zulu.Main", "Zulu")
        installLauncherApp("com.example.alpha", "com.example.alpha.Main", "Alpha")
        val apps = InstalledApps.load(context)
        assertEquals(apps.map { it.searchKey }.sorted(), apps.map { it.searchKey })
        assertEquals(
            apps.size,
            apps.distinctBy { it.packageName to it.activityName }.size,
        )
    }
}
