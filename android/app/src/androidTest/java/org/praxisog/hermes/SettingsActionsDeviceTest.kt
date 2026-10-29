package org.praxisog.hermes

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import java.io.FileInputStream

/** Disposable app and loopback host only. Never selects a default assistant. */
class SettingsActionsDeviceTest {
    private val ui = DeviceFixtureUi
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private fun shell(command: String) = ui.automation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
    }
    private fun systemSettingsVisible() = ui.find {
        it.isVisibleToUser && it.packageName?.toString() in setOf("com.android.settings", "com.android.permissioncontroller")
    } != null
    @Test fun chooseAssistantOpensAndroidSettingsWithoutChangingTheDefault() = ui.withWindows {
        val before = shell("cmd role get-role-holders android.app.role.ASSISTANT")
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { it.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column { PhoneAssistantSettings(true) {} }
            } } } }
            try {
                ui.tapText("Choose default assistant")
                ui.await("Assistant button did not open Android settings", 8000) { systemSettingsVisible() }
                ui.await("Assistant settings content missing") {
                    ui.find { it.text?.toString()?.contains("assistant", true) == true && it.packageName?.toString() != app.packageName } != null
                }
            } finally {
                if (systemSettingsVisible()) ui.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                assertEquals("Test must not change the default assistant", before, shell("cmd role get-role-holders android.app.role.ASSISTANT"))
            }
        }
    }
    private fun prepare(): HermesClient {
        ui.main { BellpullRuntime.get(app).disconnect(); BellpullSettings(app).update(BellpullPreferences()) }
        val secure = SecureConnectionStore(app); secure.clear()
        val profile = secure.add("Settings device fixture", "http://127.0.0.1:18655", "bellpull-context-device-fixture")
        secure.activate(profile.id)
        val client = HermesClient(profile.url, { "bellpull-context-device-fixture" })
        val caps = (client.capabilities() as HermesResult.Ok).value
        assertFalse(caps.notificationEvents)
        assertTrue(caps.features["bellpull_planning"] == true)
        val cache = SessionCache(app, profile.id)
        try { cache.writeCapabilities(caps.raw.toString()) } finally { cache.close() }
        return client
    }
    private fun openNotifications() {
        ui.awaitMain("Fixture connected") { BellpullRuntime.get(app).store != null }
        ui.tapText("Settings"); ui.tapText("Notifications")
        ui.await("Reply notifications setting") { ui.text("Reply notifications") != null }
    }
    private fun cleanup(client: HermesClient) {
        ui.main {
            app.stopService(Intent(app, NotificationListenerService::class.java))
            BellpullRuntime.get(app).disconnect(); BellpullSettings(app).update(BellpullPreferences())
            SecureConnectionStore(app).clear()
        }
        client.close()
    }
    /** Run separately after shell revokes the fixture POST_NOTIFICATIONS grant. */
    @Test fun deniedNotificationPermissionExplainsAndOpensRecoverySettings() = ui.withWindows {
        org.junit.Assume.assumeFalse("Revoke the fixture notification permission before this test", notificationsAllowed(app))
        val client = prepare()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                openNotifications(); ui.tapText("Reply notifications")
                ui.await("Notification permission prompt") { ui.find { it.viewIdResourceName?.endsWith("/permission_deny_button") == true } != null }
                ui.tap(checkNotNull(ui.find { it.viewIdResourceName?.endsWith("/permission_deny_button") == true }))
                ui.await("Permission refusal is explained") { ui.text(NOTIFICATIONS_BLOCKED) != null }
                assertFalse(BellpullSettings(app).value.notifications)
                scene.recreate()
                ui.await("Permission explanation survives recreation") { ui.text(NOTIFICATIONS_BLOCKED) != null }
                ui.screenshot("settings-notification-denied")
                ui.tapText("Android notification settings")
                ui.await("App notification settings opened") { systemSettingsVisible() }
                ui.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ui.await("Returned to Bellpull") { ui.text("Reply notifications") != null }
                ui.automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
                ui.tapText("Reply notifications")
                ui.await("Notifications enable after permission recovery") { BellpullSettings(app).value.notifications }
            }
        } finally { cleanup(client) }
    }

    @Test fun replyNotificationsEnableWithoutPushRelayAndTheLabelTogglesThem() = ui.withWindows {
        ui.automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val client = prepare()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                openNotifications()
                ui.tap(checkNotNull(ui.find { it.isCheckable && it.isVisibleToUser }))
                ui.await("Stock Hermes rejected reply notifications without a relay", 8000) { BellpullSettings(app).value.notifications }
                ui.await("Persistent notification connection started") {
                    app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 2401 }
                }
                ui.tapText("Reply notifications")
                ui.await("Label turns notifications off") { !BellpullSettings(app).value.notifications }
                ui.tapText("Reply notifications")
                ui.await("Label turns notifications on") { BellpullSettings(app).value.notifications }
                scene.recreate()
                ui.await("Preference survives recreation") { ui.text("Reply notifications") != null && BellpullSettings(app).value.notifications }
                ui.screenshot("settings-reply-notifications")
                ui.tapText("Reply notifications")
                ui.await("Connection stops when disabled") {
                    !BellpullSettings(app).value.notifications && app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 2401 }
                }
            }
        } finally { cleanup(client) }
    }
}
