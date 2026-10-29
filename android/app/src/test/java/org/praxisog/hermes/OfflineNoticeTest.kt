package org.praxisog.hermes

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineNoticeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bannerExpiresWithoutChangingOfflineRoutingAndReturnsForANewOutage() {
        var offline by mutableStateOf(true)
        var ready by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val notice = rememberOfflineNotice(offline)
            if (notice.visible) LocalFallbackBanner(ready, false, null, {}, {}, onDismiss = notice::dismiss)
            Text(if (offline) "Offline route" else "Online route")
        }
        compose.onNodeWithText("Offline · local model unavailable").assertExists()
        compose.mainClock.advanceTimeBy(3000)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { ready = true }
        compose.onNodeWithText("Local model ready · limited functionality").assertExists()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(2100)
        compose.onNodeWithText("Local model ready · limited functionality").assertDoesNotExist()
        compose.onNodeWithText("Offline route").assertExists()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { offline = false }
        compose.onNodeWithText("Online route").assertExists()
        compose.runOnIdle { offline = true }
        compose.onNodeWithText("Local model ready · limited functionality").assertExists()
    }
    @Test fun manualDismissalSurvivesActivityRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val notice = rememberOfflineNotice(true)
            if (notice.visible) LocalFallbackBanner(false, false, null, {}, {}, onDismiss = notice::dismiss)
        }
        compose.onNodeWithText("Dismiss").performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Offline · local model unavailable").assertDoesNotExist()
    }
}
