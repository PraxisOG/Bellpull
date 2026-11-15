package org.praxisog.hermes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ConnectionStatusBannerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reconnectingKeepsTheServerAndOffersRetryWithoutLocalModelPrompts() {
        var retries = 0; var local = 0
        compose.setContent { HermesTheme {
            ConnectionStatusBanner(ConnectionHealth(ConnectionHealth.Phase.RECONNECTING), { retries++ }, {}, { local++ })
        } }
        compose.onNodeWithText("Reconnecting to server…").assertIsDisplayed()
        compose.onNodeWithText("Your chat stays on this server.").assertIsDisplayed()
        compose.onNodeWithText("Create local fork").assertDoesNotExist()
        compose.onNodeWithText("Load / retry").assertDoesNotExist()
        compose.onNodeWithText("Retry connection").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, local) }
    }
}
