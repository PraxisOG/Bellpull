package org.praxisog.hermes

/**
 * An installed app the user could launch. Deliberately a plain data class with no Android
 * types, so screens and filtering can be tested without a PackageManager.
 */
internal data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String,
) {
    /** Lowercased once at construction; the filter runs on every keystroke. */
    val searchKey: String = label.lowercase()
}

/**
 * Ranks [apps] against [query]. Prefix matches come before contains-matches, because typing
 * "sig" should surface Signal before "Podcast Signal Booster"; within a tier, alphabetical,
 * so the order never depends on however the PackageManager happened to return things.
 */
internal fun searchApps(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val prefix = mutableListOf<LaunchableApp>()
    val contains = mutableListOf<LaunchableApp>()
    for (app in apps) {
        when {
            app.searchKey.startsWith(q) -> prefix += app
            app.searchKey.contains(q) -> contains += app
        }
    }
    val byLabel = compareBy<LaunchableApp> { it.searchKey }
    return prefix.sortedWith(byLabel) + contains.sortedWith(byLabel)
}
