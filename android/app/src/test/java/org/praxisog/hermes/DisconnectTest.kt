package org.praxisog.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen half of the lost-phone path. The storage half lives in
 * `androidTest/CredentialClearingTest`: EncryptedSharedPreferences needs a real
 * AndroidKeyStore, which Robolectric does not provide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisconnectTest {

    @get:Rule val compose = createComposeRule()

    @Test fun theConnectionScreenBlanksWhenTheStoredKeyIsCleared() {
        // The bug this guards: initialUrl/initialKey were captured once in onCreate, and the
        // screen remembered them unkeyed, so the old key stayed on screen after Disconnect.
        var url by mutableStateOf("https://host.tail0b3e3a.ts.net")
        compose.setContent {
            ConnectionScreen(url, "", "status", false) { _, _ -> }
        }
        compose.onNodeWithText("https://host.tail0b3e3a.ts.net").assertExists()

        url = ""
        compose.waitForIdle()
        compose.onNodeWithText("https://host.tail0b3e3a.ts.net").assertDoesNotExist()
    }
}
