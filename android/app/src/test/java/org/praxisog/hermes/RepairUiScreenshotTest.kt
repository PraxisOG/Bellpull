package org.praxisog.hermes

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class RepairUiScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val answer = """
        ## Reading nook
        A **soft chair**, a *warm lamp*, and `notes.md`. [Reference](https://example.com/nook)

        1. Measure the window.
        2. Leave room for the door.

        ```kotlin
        val room = "A very long line that should scroll inside the code block without widening the chat window"
        println(room)
        ```

        | Item | Placement | Cost |
        | :--- | :---: | ---: |
        | Chair | Window | 120 |
        | Lamp | Table | 35 |
    """.trimIndent()
    private fun markdown(scale: Float = 1f) {
        compose.setContent { HermesTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    MarkdownAnswer(answer)
                } }
            }
        } }
        compose.onNodeWithText("Reading nook").assertIsDisplayed()
        compose.onNodeWithText("## Reading nook").assertDoesNotExist()
        compose.onRoot().captureRoboImage()
    }
    @Test fun markdownCover() {
        markdown()
        compose.onNodeWithTag("copy-code").performScrollTo().performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(answer.substringAfter("```kotlin\n").substringBefore("```"), clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithTag("code-scroll").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("table-scroll").performScrollTo().performTouchInput { swipeLeft() }
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun markdownUnfoldedDark() = markdown()
    @Test fun markdownLargeTextCover() = markdown(1.5f)

    private fun navigation(labeled: Boolean) {
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalLabeledControls provides labeled) {
                Column {
                    ConversationToolbar("Reading nook", true, true, {}, {}, false, true, true, false, {}, {}, {})
                    SessionListScreen(UiState.Ready(UiFixtures.sessions), UiFixtures.NOW, UiFixtures.sessions.first().id,
                        {}, {}, {}, titleOnly = true, source = null, onSourceChange = {}, onExpandedSearch = {},
                        unreadIds = setOf(UiFixtures.sessions.first().id))
                }
            }
        } }
        UiFixtures.sessions.first().preview?.let { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Unread reply").assertExists()
        compose.onRoot().captureRoboImage()
    }
    @Test fun titleOnlyCompactCover() = navigation(false)
    @Test fun titleOnlyLabeledCover() = navigation(true)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun titleOnlyLabeledUnfoldedDark() = navigation(true)

    @Test fun defaultsAreDenseAndExistingPreferencesSurvive() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("bellpull_presentation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertTrue(BellpullSettings(context).value.denseChatWindow)
        assertTrue(BellpullSettings(context).value.titleOnlyConversations)
        prefs.edit().putBoolean("dense_chat_window", false).commit()
        assertFalse(BellpullSettings(context).value.denseChatWindow)
    }
}
