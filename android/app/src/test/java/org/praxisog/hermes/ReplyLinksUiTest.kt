package org.praxisog.hermes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ReplyLinksUiTest {
    @get:Rule val compose = createComposeRule()
    private val opened = mutableListOf<String>()

    private fun render(source: String, live: Boolean = false, dense: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                HermesTheme {
                    SessionDetailScreen(
                        UiState.Ready(if (live) emptyList() else listOf(Message(1, role = "assistant", content = source))),
                        if (live) TurnState.Streaming(source) else TurnState.Idle,
                        { false }, {}, {}, denseChatWindow = dense, reduceMotion = true,
                    )
                }
            }
        }
    }

    private fun touch(text: String, target: String, long: Boolean = false) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point = layouts.single().getBoundingBox(text.indexOf(target) + target.length / 2).center
        node.performTouchInput { if (long) longClick(point) else click(point) }
    }

    @Test fun savedMarkdownLinkOpensByTouch() {
        render("Read [the guide](https://example.com/guide) now.")
        touch("Read the guide now.", "the guide")
        assertEquals(listOf("https://example.com/guide"), opened)
    }

    @Test fun liveDenseMarkdownLinkOpensByTouch() {
        render("Read [the guide](https://example.com/guide) now.", live = true, dense = true)
        touch("Read the guide now.", "the guide")
        assertEquals(listOf("https://example.com/guide"), opened)
    }

    @Test fun bareUrlsOpenByTouch() {
        val source = "Visit https://example.com/guide or www.example.org."
        render(source, dense = true)
        touch(source, "https://example.com/guide")
        touch(source, "www.example.org")
        assertEquals(listOf("https://example.com/guide", "http://www.example.org"), opened)
    }

    @Test fun messageLongPressStillOffersSelection() {
        render("Read [the guide](https://example.com/guide) now.")
        touch("Read the guide now.", "Read", long = true)
        compose.onNodeWithTag("message-action-popup").assertExists()
        compose.onNode(hasContentDescription("Select text") and hasAnyAncestor(hasTestTag("message-action-popup")))
            .performClick()
        compose.onNodeWithTag("selected-message-text").assertExists()
        assertTrue(opened.isEmpty())
    }

    private fun linksScreenshot() {
        render("Read [the guide](https://example.com/guide), visit https://example.com/help " +
            "or www.example.org.\n\n`https://example.com/code` stays code.", dense = true)
        compose.waitForIdle()
        captureScreenRoboImage()
    }
    @Test fun linksCover() = linksScreenshot()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun linksUnfoldedDark() = linksScreenshot()
}
