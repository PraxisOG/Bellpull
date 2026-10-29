package org.praxisog.hermes

import android.Manifest
import android.content.*
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Disposable package, loopback fake inference, synthetic shell alert and LOCAL calendar only. */
class PhoneContextDeviceTest {
    @Test fun phoneSourcesReachPluginAndNewNativeChatThenRevocationClearsThem() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi
        val app = ApplicationProvider.getApplicationContext<Context>()
        ui.guard()
        val origin = "http://127.0.0.1:18655"
        val key = "bellpull-context-device-fixture"
        val client = HermesClient(origin, { key })
        fun <T> ok(value: HermesResult<T>): T = when (value) { is HermesResult.Ok -> value.value; is HermesResult.Err -> error(value.error.toString()) }
        fun control(): JsonObject {
            val request = URI("$origin/fixture/context").toURL().openConnection() as HttpURLConnection
            request.connectTimeout = 5000; request.readTimeout = 5000
            request.setRequestProperty("Authorization", "Bearer $key")
            return try { assertEquals(200, request.responseCode); request.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject } }
            finally { request.disconnect() }
        }
        fun shell(command: String) = ui.automation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
        val profile = "phone-context-device"
        val cache = SessionCache(app, profile)
        val repo = PhoneNotificationRepository.get(app)
        fun idle() = runBlocking { repo.awaitIdle() }
        val component = ComponentName(app, PhoneContextListenerService::class.java).flattenToString()
        ui.automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_CALENDAR)
        ui.automation.grantRuntimePermission(app.packageName, Manifest.permission.WRITE_CALENDAR)
        val account = "bellpull-device-" + UUID.randomUUID().toString()
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter("account_name", account).appendQueryParameter("account_type", CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val inserted = checkNotNull(app.contentResolver.insert(uri, ContentValues().apply {
            put("account_name", account); put("account_type", CalendarContract.ACCOUNT_TYPE_LOCAL); put("name", account)
            put("calendar_displayName", "Bellpull synthetic context calendar"); put("calendar_color", 0xff59091e.toInt())
            put("calendar_access_level", CalendarContract.Calendars.CAL_ACCESS_OWNER); put("ownerAccount", account)
            put("visible", 1); put("sync_events", 1); put("calendar_timezone", "UTC")
        }))
        val calendarId = ContentUris.parseId(inserted)
        lateinit var sync: PhoneContextSync
        var syncCreated = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var store: HermesStore? = null
        try {
            val phone = PhoneCalendar(app)
            val source = phone.sources().single { it.id == calendarId }
            val policy = CalendarPolicy(setOf(source.key), sharing = true, busyOnly = false, writes = true, destination = source.key)
            val now = System.currentTimeMillis()
            phone.create(UUID.randomUUID().toString().replace("-", ""), policy, source,
                CalendarDraft("Synthetic museum appointment", now+600_000, now+3_600_000))
            cache.writeCalendarPolicy(policy.copy(writes = false))
            idle(); repo.clear(); idle()
            shell("cmd notification allow_listener $component")
            repo.capture(true); idle(); requestPhoneNotificationBinding(app)
            ui.await("Synthetic listener connected") { repo.state.value.connected }
            shell("cmd notification post -t Rowan-context-fixture bellpull-context-fixture Meet-at-Cobalt-Orchard")
            ui.await("Synthetic notification captured") { repo.state.value.records.any { it.title == "Rowan-context-fixture" } }
            repo.shareServer(profile, true); idle()
            val caps = ok(client.capabilities()); assertTrue(caps.features["bellpull_phone_context"] == true)
            client.configureCapabilities(caps)
            ui.main {
                sync = PhoneContextSync(app, profile, cache); syncCreated = true
                sync.settings.update(PhoneContextConfiguration(calendarOnline = true))
                sync.configure(client, true)
            }
            ui.await("Phone context synced", 30_000) { control()["context"]!!.jsonArray.toString().contains("Synthetic museum appointment") }
            val before = control()
            assertEquals(0, before["runs"]!!.jsonPrimitive.int)
            assertTrue(before["context"].toString().contains("Cobalt-Orchard"))
            assertTrue(ok(client.allSessions()).data.isEmpty())
            val session = ok(client.createSession("Synthetic phone context chat"))
            ui.main {
                store = HermesStore(scope, client, durableRetention = caps.durableRunRetention).also { it.openSession(session.id) }
            }
            ui.awaitMain("New chat loaded") { store!!.history is UiState.Ready }
            ui.main { assertTrue(store!!.send("What is coming up?")) }
            ui.await("Native run received phone context", 30_000) { control()["runs"]!!.jsonPrimitive.int == 1 }
            val prompt = control()["prompt"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("Cobalt-Orchard")); assertTrue(prompt.contains("Synthetic museum appointment"))
            assertTrue(prompt.contains("untrusted"))
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PhoneContextSettingsUi(sync, repo, ServerProfile(profile, "Synthetic context fixture", origin), true)
                    }
                } } } }
                ui.await("Context settings rendered") { ui.text("New-chat phone context") != null }
                ui.screenshot("phase15-phone-context")
            }
            repo.shareServer(profile, false); idle()
            ui.main { sync.settings.update(PhoneContextConfiguration()); sync.request() }
            ui.await("Revocation reached plugin", 30_000) { control()["context"]!!.jsonArray.isEmpty() }
            assertEquals(1, control()["runs"]!!.jsonPrimitive.int)
        } finally {
            ui.main { store?.close(); if (syncCreated) sync.close()
                repo.state.value.records.filter { it.title == "Rowan-context-fixture" }.forEach {
                    PhoneContextListenerService.fixtureInstance?.cancelNotification(it.sourceKey)
                }
            }
            scope.cancel(); repo.capture(false); repo.clear(); idle(); shell("cmd notification disallow_listener $component")
            app.contentResolver.delete(uri, "_id=?", arrayOf(calendarId.toString()))
            cache.clear(); cache.close(); client.close()
        }
    }
}
