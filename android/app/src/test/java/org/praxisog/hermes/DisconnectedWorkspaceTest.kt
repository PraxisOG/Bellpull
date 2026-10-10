package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class DisconnectedWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private var sends = 0
    @Composable private fun App() {
        var id by remember { mutableStateOf<String?>(null) }
        var draft by remember { mutableStateOf(TextFieldValue()) }
        HermesApp(UiState.Ready(listOf(BellpullExample.session)), UiState.Ready(BellpullExample.messages),
            TurnState.Idle, id, BellpullExample.session.takeIf { id != null }, 0.0,
            { id = it }, { id = null }, { sends++ }, {}, {}, {}, { id = null },
            draft = draft, onDraftChange = { draft = it }, disconnected = true,
            serverProfiles = { ServerProfilesPanel(emptyList(), null, "Explore Bellpull, or add your own server.", false,
                { _, _, _ -> true }, {}, {}, {}, {}, { _, _ -> }) })
    }
    @Test fun welcomeCover() { compose.setContent { App() }; compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun welcomeUnfoldedDark() {
        compose.setContent { App() }; compose.onRoot().captureRoboImage()
    }
    @Test fun sampleIsExplicitAndNeverSends() {
        compose.setContent { App() }
        compose.onNodeWithText("Explore a sample chat").performClick()
        compose.onNodeWithText("Local example · No server connected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("Local draft")
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, sends) }
    }
    @Test fun settingsCover() {
        compose.setContent { App() }
        compose.onNodeWithText("Open Settings").performClick()
        compose.onNodeWithText("Server profiles").assertIsDisplayed()
        compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w840dp-h900dp") fun settingsUnfolded() {
        compose.setContent { App() }
        compose.onNodeWithText("Open Settings").performClick()
        compose.onRoot().captureRoboImage()
    }
    @Test fun credentialsRequireAnExplicitRemovalConfirmation() {
        var removes = 0
        compose.setContent { HermesTheme { androidx.compose.foundation.layout.Column {
            ServerProfilesPanel(listOf(ServerProfile("test", "Desktop", "https://example.test")), "test", "Using Desktop.", false,
                { _, _, _ -> true }, {}, {}, { removes++ }, { removes++ }, { _, _ -> })
        } } }
        compose.onNodeWithText("Remove all credentials").performClick()
        compose.runOnIdle { assertEquals(0, removes) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, removes) }
        compose.onNodeWithText("Remove all credentials").performClick()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        compose.runOnIdle { assertEquals(1, removes) }
    }
    @Composable private fun ProfileSettings() {
        HermesTheme { SettingsWorkspace(BellpullPreferences(), {}, serverProfiles = {
            ServerProfilesPanel(listOf(ServerProfile("a", "Desktop testing", "https://desktop.example"),
                ServerProfile("b", "Compute server", "https://compute.example")), "a", "Using Desktop testing.", false,
                { _, _, _ -> true }, {}, {}, {}, {}, { _, _ -> })
        }) }
    }
    @Test fun savedProfilesCover() {
        compose.setContent { ProfileSettings() }; compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun savedProfilesUnfoldedDark() {
        compose.setContent { ProfileSettings() }; compose.onRoot().captureRoboImage()
    }
}
