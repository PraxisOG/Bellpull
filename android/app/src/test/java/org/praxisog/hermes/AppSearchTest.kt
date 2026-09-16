package org.praxisog.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private fun app(label: String, pkg: String = label.lowercase().replace(" ", ".")) =
        LaunchableApp(label = label, packageName = pkg, activityName = "$pkg.Main")

    private val installed = listOf(
        app("Signal"),
        app("Podcast Signal Booster"),
        app("Organic Maps"),
        app("Aegis Authenticator"),
        app("signal-cli helper"),
    )

    @Test fun emptyQueryMatchesNothingRatherThanEverything() {
        // The search results view is only shown for a non-blank query; returning the whole
        // list here would make an accidental space render every installed app.
        assertTrue(searchApps(installed, "").isEmpty())
        assertTrue(searchApps(installed, "   ").isEmpty())
    }

    @Test fun prefixMatchesRankAboveContainsMatches() {
        val labels = searchApps(installed, "sig").map { it.label }
        // "Signal" and "signal-cli helper" start with the query; the Podcast one only
        // contains it, so it must not come first.
        assertEquals(listOf("Signal", "signal-cli helper", "Podcast Signal Booster"), labels)
    }

    @Test fun matchingIsCaseInsensitive() {
        assertEquals(listOf("Organic Maps"), searchApps(installed, "ORGANIC").map { it.label })
        assertEquals(listOf("Organic Maps"), searchApps(installed, "maps").map { it.label })
    }

    @Test fun orderIsStableAndNotDependentOnInputOrder() {
        // The PackageManager's ordering is not contractual, so the same query against a
        // shuffled list must produce the same ranking.
        val shuffled = installed.reversed()
        assertEquals(
            searchApps(installed, "s").map { it.label },
            searchApps(shuffled, "s").map { it.label },
        )
    }

    @Test fun nonMatchingQueryReturnsNothing() {
        assertTrue(searchApps(installed, "zzzz").isEmpty())
    }

    @Test fun queryIsTrimmedBeforeMatching() {
        assertEquals(listOf("Signal"), searchApps(installed, "  signal ").map { it.label }.take(1))
    }
}
