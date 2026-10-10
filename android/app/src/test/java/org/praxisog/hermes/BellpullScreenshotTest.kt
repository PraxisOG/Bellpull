package org.praxisog.hermes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BellpullScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(content: @Composable () -> Unit) {
        compose.setContent { HermesTheme { content() } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage()
    }

    @Test @Config(qualifiers = "w393dp-h850dp") fun settingsCover() = capture {
        SettingsWorkspace(BellpullPreferences(), {})
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun settingsUnfoldedDark() = capture {
        SettingsWorkspace(BellpullPreferences(Appearance.DARK, reduceMotion = true), {})
    }
    @Test @Config(qualifiers = "w393dp-h850dp") fun searchCover() = capture {
        WorkspaceSearch(UiFixtures.sessions, emptyList(), UiFixtures.NOW, null, "", {}, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun searchUnfoldedDark() = capture {
        WorkspaceSearch(UiFixtures.sessions, emptyList(), UiFixtures.NOW, null, "", {}, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = "w393dp-h850dp") fun liveToolsAndThinkingCover() = capture {
        SessionDetailScreen(UiState.Ready(listOf(Message(1, role = "user", content = "Check both references."))),
            TurnState.Streaming("The first source agrees.", canStop = true, parts = listOf(
                LivePart.Prose("r:1", "I will compare the dates.", thinking = true),
                LivePart.Tools("r:2", listOf(LiveTool("r:2", "web_search", "Search references", false),
                    LiveTool("r:3", "web_extract", "Read source", true))),
                LivePart.Prose("r:4", "\n\nThe first source agrees."))), {}, {}, {}, reduceMotion = true)
    }
    @Test @Config(qualifiers = "w393dp-h850dp-night") fun expandedThinkingDark() = capture {
        SessionDetailScreen(UiState.Ready(listOf(Message(1, role = "assistant", content = "The answer is 42.",
            reasoningContent = "Check the arithmetic from the supplied values."))),
            TurnState.Idle, {}, {}, {}, collapseThinking = false)
    }
    @Test @Config(qualifiers = "w393dp-h850dp") fun toolFirstWithWhitespaceCover() = capture {
        SessionDetailScreen(UiState.Ready(listOf(Message(2, role = "assistant", content = "\n\n"))),
            TurnState.Streaming("\n\n", canStop = true, parts = listOf(
                LivePart.Prose("blank", "\n\n"),
                LivePart.Tools("tools", listOf(LiveTool("search", "web_search", "Search references", true))))),
            {}, {}, {}, pendingUserText = "Look up the reference.", reduceMotion = true)
    }
}
