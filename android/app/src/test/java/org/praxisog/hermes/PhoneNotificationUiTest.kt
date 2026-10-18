package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class PhoneNotificationUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-18T17:00:00Z").toEpochMilli()
    private val row = PhoneNotificationRecord("one", "key", "org.example.messages", "Messages", "Alice · Workshop",
        "The workshop moved to noon. Bring the prototype.", now, now, now,
        previous = listOf(NotificationRevision("Alice · Workshop", "Meet at eleven.", now - 60_000)), updateCount = 1)
    private val profile = ServerProfile("server", "Home Hermes", "https://hermes.example")
    private lateinit var oldZone: TimeZone
    @Before fun zone() { oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restoreZone() { TimeZone.setDefault(oldZone) }
    private fun controls(state: PhoneNotificationSnapshot = PhoneNotificationSnapshot(ready = true),
        capture: (Boolean) -> Unit = {}, share: (Boolean) -> Unit = {}, clear: () -> Unit = {}) {
        compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneNotificationControls(state, profile, capture, {}, share, {}, {}, { _, _ -> }, clear)
            }
        } } }
    }
    @Test fun settingsCover() { controls(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun settingsUnfolded() {
        controls(PhoneNotificationSnapshot(ready = true, policy = PhoneNotificationPolicy(capture = true), access = true, connected = true, records = listOf(row)))
        compose.onRoot().captureRoboImage()
    }
    private fun history() {
        compose.setContent { HermesTheme { PhoneNotificationHistory(listOf(row, row.copy(id = "two", title = "Build complete", text = "Release checks passed.", appName = "Build monitor", removedAt = now, removalReason = "dismissed")), {}, { _, _ -> }) } }
    }
    @Test fun historyCover() { history(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun historyUnfolded() { history(); compose.onRoot().captureRoboImage() }
    @Test fun grantCaptureDoesNotImplySharingAndSharingRequiresItsOwnConfirmation() {
        var capture = false; var shared = false
        controls(PhoneNotificationSnapshot(ready = true, policy = PhoneNotificationPolicy(capture = true), access = true),
            { capture = it }, { shared = it })
        compose.onNodeWithContentDescription("Share relevant excerpts").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(shared) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertFalse(shared) }
        compose.onNodeWithContentDescription("Share relevant excerpts").performScrollTo().performClick()
        compose.onNodeWithText("Enable sharing").performClick()
        compose.runOnIdle { assertTrue(shared); assertFalse(capture) }
    }
    @Test fun clearNeedsConfirmationAndHistorySearchAndDetailsWork() {
        var cleared = false
        controls(PhoneNotificationSnapshot(ready = true, records = listOf(row)), clear = { cleared = true })
        compose.onNodeWithText("View history (1)").performScrollTo().performClick()
        compose.onNodeWithTag("notification-search").performTextInput("unrelated")
        compose.onNodeWithText("No matching notifications.").assertExists()
        compose.onNodeWithTag("notification-search").performTextClearance()
        compose.onNodeWithText("Details and revisions").performClick()
        compose.onNodeWithText("Earlier · Oct 18, 16:59\nAlice · Workshop\nMeet at eleven.").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Clear history").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(cleared) }
        compose.onNodeWithText("Delete history").performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }
    @Test fun sharedContextIsFoldedAndOriginalPromptStaysReadable() {
        val context = notificationContextText(listOf(row), true)!!
        compose.setContent { HermesTheme { Column { UserMessageText("Summarize my notifications" + context) } } }
        compose.onNodeWithText("Summarize my notifications").assertIsDisplayed()
        compose.onNodeWithText(context.trim()).assertDoesNotExist()
        compose.onNodeWithTag("phone-context-toggle").performClick()
        compose.onNodeWithText(context.trim()).assertExists()
    }
}
