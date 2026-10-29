package org.praxisog.hermes

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

// Availability of ROLE_ASSISTANT does not mean apps can request it. Android's
// role policy marks it non-requestable; its public Settings screen owns selection.
internal fun assistantSettingsIntents() = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

internal fun notificationSettingsIntents(packageName: String) = listOf(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

internal fun launchSystemSettings(intents: List<Intent>, launch: (Intent) -> Unit): Boolean {
    for (intent in intents) {
        try { launch(intent); return true }
        catch (_: ActivityNotFoundException) { /* Try the broader public Settings page. */ }
        catch (_: SecurityException) { /* Some device settings handlers are restricted. */ }
    }
    return false
}

internal const val NOTIFICATIONS_BLOCKED = "Android notifications are blocked. Allow notifications in Android settings, then enable Reply notifications."
internal fun notificationsAllowed(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
