package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

/**
 * Reads the launchable apps via `ACTION_MAIN` + `CATEGORY_LAUNCHER`, which is exactly the set
 * declared in the manifest's `<queries>` element and needs no permission.
 *
 * Kept behind a function type at the call site so the UI can be tested with a fixed list;
 * this is the only place that touches [PackageManager].
 */
internal object InstalledApps {

    fun load(context: Context): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        // Flags 0, NOT MATCH_DEFAULT_ONLY. MATCH_DEFAULT_ONLY restricts results to filters
        // carrying CATEGORY_DEFAULT, which launcher activities do not declare — it is for
        // resolving an implicit intent to a default handler, a different job. Using it here
        // returns an empty list, on a device as well as in a test.
        val resolved: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrElse { return emptyList() }

        return resolved
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: activity.packageName
                LaunchableApp(
                    label = label,
                    packageName = activity.packageName,
                    activityName = activity.name,
                )
            }
            // Our own launcher entry is noise in our own search results.
            .filterNot { it.packageName == context.packageName }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.searchKey }
    }

    /**
     * Launches [app] by explicit component. `NEW_TASK` because the target belongs in its own
     * task rather than this app's back stack — this is a normal app opening another app, not
     * a launcher shell hosting it.
     */
    fun launch(context: Context, app: LaunchableApp): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.packageName, app.activityName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
