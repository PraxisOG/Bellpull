package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class VoicePromptDisclosureTest {
    @get:Rule val compose = createComposeRule()
    private val words = "Give me the full research answer, like a podcast."
    private val original = Message(901, role = "user", content = "$VOICE_PRESET\n\n$words")
    @Composable private fun Bubble(dense: Boolean = true) {
        HermesTheme(appearance = Appearance.DARK, palette = Palette.MAROON) {
            MessageBubble(original, dense, {})
        }
    }
    @Test fun presetStartsCollapsedAndTapShowsAndHidesOnlyTheDetails() {
        compose.setContent { Bubble() }
        val toggle = compose.onNodeWithTag("voice-preset-toggle")
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithText(words).assertIsDisplayed()
        compose.onNodeWithText(VOICE_PRESET).assertDoesNotExist()
        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText(VOICE_PRESET).assertIsDisplayed()
        compose.onNodeWithText(words).assertIsDisplayed()
        toggle.performClick()
        compose.onNodeWithText(VOICE_PRESET).assertDoesNotExist()
    }
    @Test fun expansionSurvivesRecreationAndKeepsIndependentMessagesCollapsed() {
        val restore = StateRestorationTester(compose)
        restore.setContent { Column { Bubble(); HermesTheme { MessageBubble(original.copy(id = 902), true, {}) } } }
        compose.onAllNodesWithTag("voice-preset-toggle")[0].performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithTag("voice-preset-toggle")[0]
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onAllNodesWithTag("voice-preset-toggle")[1]
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }
    @Test fun copyAndEditActionsReceiveTheCompleteOriginalMessage() {
        var edited: Message? = null
        compose.setContent { HermesTheme { MessageBubble(original, true, {}, canEdit = true, onEdit = { edited = it }) } }
        fun popup() = compose.onNodeWithText(words, useUnmergedTree = true).performTouchInput { longClick() }
        popup(); compose.onNodeWithContentDescription("Copy message").performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(original.content, clipboard.primaryClip!!.getItemAt(0).text.toString())
        popup(); compose.onNodeWithContentDescription("Fork & edit").performClick()
        assertEquals(original, edited)
    }
    @Test fun pendingMessagesUseTheSameCollapsedDisplay() {
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(emptyList()), TurnState.Idle, { false }, {}, {}, pendingUserText = original.content)
        } }
        compose.onNodeWithTag("voice-preset-toggle").assertExists()
        compose.onNodeWithText(VOICE_PRESET).assertDoesNotExist()
        compose.onNodeWithText(words).assertIsDisplayed()
    }
    @Test fun assistantAndOrdinaryUserTextAreNotFolded() {
        compose.setContent { HermesTheme {
            Column { MessageBubble(original.copy(role = "assistant"), true, {}); MessageBubble(original.copy(id = 902, content = "Example: $VOICE_PRESET"), true, {}) }
        } }
        compose.onNodeWithTag("voice-preset-toggle").assertDoesNotExist()
        compose.onNodeWithText(original.content!!).assertExists()
    }
    @Test fun collapsedCover() { compose.setContent { Bubble() }; compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun expandedUnfolded() {
        compose.setContent { Bubble(false) }
        compose.onNodeWithTag("voice-preset-toggle").performClick()
        compose.onRoot().captureRoboImage()
    }
}
