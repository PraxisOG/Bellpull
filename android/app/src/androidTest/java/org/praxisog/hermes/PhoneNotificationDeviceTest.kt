package org.praxisog.hermes

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class PhoneNotificationDeviceTest {
    private val ui = DeviceFixtureUi
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun shell(command: String): String = ui.automation.executeShellCommand(command).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
    }
    private fun idle(repository: PhoneNotificationRepository) = runBlocking { repository.awaitIdle() }
    private fun reveal(label: String) {
        repeat(25) { if (ui.text(label) != null) { ui.tapText(label); return }; ui.scroll(true) }
        fail("Missing $label")
    }
    @Test fun realListenerCapturesUpdatesReconcilesRestartAndRevocationStopsNewSharing() = ui.withWindows {
        ui.guard()
        val repo = PhoneNotificationRepository.get(context)
        val component = ComponentName(context, PhoneContextListenerService::class.java).flattenToString()
        val tag = "bellpull-phase12-fixture"
        try {
            idle(repo); repo.clear(); idle(repo)
            shell("cmd notification allow_listener $component")
            repo.capture(true); idle(repo)
            requestPhoneNotificationBinding(context)
            ui.await("Android notification listener connected") { repo.state.value.connected }
            shell("cmd notification post -t Phase12-synthetic-Alice $tag Workshop-at-noon")
            ui.await("Synthetic notification captured") { repo.state.value.records.any { it.title == "Phase12-synthetic-Alice" } }
            val first = repo.state.value.records.first { it.title == "Phase12-synthetic-Alice" }
            assertEquals("com.android.shell", first.packageName)
            assertTrue(repo.state.value.records.all { it.packageName == "com.android.shell" })
            shell("cmd notification post -t Phase12-synthetic-Alice $tag Workshop-moved-to-one")
            ui.await("Notification update coalesced") { repo.state.value.records.any { it.id == first.id && it.text == "Workshop-moved-to-one" } }
            assertEquals(1, repo.state.value.records.count { it.title == first.title })
            ui.main { checkNotNull(PhoneContextListenerService.fixtureInstance).cancelNotification(first.sourceKey) }
            ui.await("Android removal captured") { repo.state.value.records.first { it.id == first.id }.removedAt != null }
            repo.share("fixture-server", true); idle(repo)
            assertNotNull(repo.contextFor("fixture-server", "Alice")); assertNull(repo.contextFor("another-server", "Alice"))
            val reopened = PhoneNotificationStorage(context)
            try { assertTrue(reopened.read(System.currentTimeMillis(), reopened.policy()).any { it.id == first.id && it.text == "Workshop-moved-to-one" }) }
            finally { reopened.close() }
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme {
                    val snapshot by repo.state.collectAsState()
                    PhoneNotificationHistory(snapshot.records, {}, { pkg, excluded -> repo.exclude(pkg, excluded) })
                } } }
                ui.await("History on device") { ui.text("Workshop-moved-to-one") != null }
                ui.screenshot("phase12-history")
                scene.recreate()
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhoneNotificationSettings(repo, ServerProfile("fixture-server", "Fixture server", "https://fixture.invalid"))
                    }
                } } } }
                ui.await("Notification settings on device") { ui.text("Capture phone notifications") != null }
                ui.screenshot("phase12-settings")
                reveal("Clear history")
                ui.tapText("Delete history")
                ui.await("Clear committed") { repo.state.value.records.isEmpty() }
            }
            shell("cmd notification disallow_listener $component")
            repo.refresh(); idle(repo)
            assertNull(repo.contextFor("fixture-server", "notifications"))
            assertFalse(repo.state.value.policy.capture)
            assertTrue(repo.state.value.policy.sharingProfiles.isEmpty())
            ui.instrumentation.sendStatus(0, Bundle().apply { putString("phase12_listener", "capture, update deduplication, encrypted reopen, UI recreation, clear and revocation passed; only synthetic shell content accepted") })
        } finally {
            val remaining = repo.state.value.records.filter { it.packageName == "com.android.shell" && it.title == "Phase12-synthetic-Alice" }
            ui.main { remaining.forEach { PhoneContextListenerService.fixtureInstance?.cancelNotification(it.sourceKey) } }
            repo.capture(false); repo.clear(); idle(repo)
            shell("cmd notification disallow_listener $component")
        }
    }
    @Test fun encryptedBurstCaptureStaysBoundedOffTheUiThread() = ui.withWindows {
        ui.guard()
        val name = "phase12-performance.db"
        val repository = PhoneNotificationRepository({ PhoneNotificationStorage(context, databaseName = name) }, { true }, context.packageName)
        try {
            idle(repository); repository.capture(true); idle(repository)
            val now = System.currentTimeMillis()
            val start = SystemClock.elapsedRealtime()
            repeat(200) { i ->
                repository.posted(PhoneNotificationRecord("", "synthetic-$i", "com.android.shell", "Synthetic source", "Synthetic $i",
                    "A bounded notification body for performance testing.", now, now, now))
            }
            idle(repository)
            val elapsed = SystemClock.elapsedRealtime() - start
            assertEquals(200, repository.state.value.records.size)
            assertTrue("200 encrypted inserts took ${elapsed}ms", elapsed < 15_000)
            val raw = context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("bounded notification body"))
            ui.instrumentation.sendStatus(0, Bundle().apply { putString("phase12_burst", "200 encrypted inserts in ${elapsed}ms; no plaintext body in database") })
        } finally { repository.clear(); idle(repository); runBlocking { repository.close() }; context.deleteDatabase(name) }
    }
}
