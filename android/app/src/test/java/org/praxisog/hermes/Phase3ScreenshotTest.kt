package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class Phase3ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val selection = ModelSelection("qwen38-flash-next", "custom")
    private val catalog = ModelCatalog(1, listOf(ModelRoute(selection.model, selection.provider, true,
        reasoning = ReasoningCapabilities(canDisable = true, canEnable = true, note = "Verified thinking on/off. Graded effort is not exposed by this local model."))), selection,
        "Configured provider catalog. Availability is confirmed when the provider accepts a request.")
    private fun picker() {
        compose.setContent { HermesTheme(palette = Palette.MAROON) {
            ModelPicker(catalog, selection, null, false, {}, { _, _ -> }, {})
        } }
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("On").assertIsDisplayed()
        captureScreenRoboImage()
    }
    @Test fun localModelCover() = picker()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun localModelUnfoldedDark() = picker()
    @Test fun attachmentBrowserCover() {
        val items = listOf(Artifact("a".repeat(32), UiFixtures.sessions[0].id, "studio-plan.pdf", "application/pdf", 130000,
            "0".repeat(64), UiFixtures.NOW, "agent"),
            Artifact("b".repeat(32), UiFixtures.sessions[0].id, "reference-photo.png", "image/png", 40000,
                "0".repeat(64), UiFixtures.NOW - 100, "upload", available = false))
        compose.setContent { HermesTheme(palette = Palette.MAROON) { Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachmentBrowser(items, UiFixtures.sessions, null, {}, {}, {})
            }
        } } }
        compose.waitForIdle(); compose.onRoot().captureRoboImage()
    }
    @Test fun incomingShareIsADraftChoice() {
        var picked: String? = "unchanged"
        compose.setContent { HermesTheme {
            IncomingShareDialog(IncomingShare("Review these notes", emptyList()), UiFixtures.sessions, true, false, null,
                { picked = it }, {}, {})
        } }
        compose.waitForIdle(); assertEquals("unchanged", picked)
        compose.onNodeWithText("New conversation").performClick(); assertNull(picked)
    }
    @Test fun unknownReasoningDoesNotInventOffSwitch() {
        compose.setContent { HermesTheme {
            ModelPicker(catalog.copy(models = listOf(ModelRoute(selection.model, selection.provider, true))), selection, null, false, {}, { _, _ -> }, {})
        } }
        compose.onNodeWithText("Off").assertDoesNotExist()
        compose.onNodeWithText("On").assertDoesNotExist()
    }
    @Test fun browserSortsFiltersAndReturnsToSourceConversation() {
        val sessions = listOf(Session("first", title = "First source"), Session("second", title = "Second source"))
        val items = listOf(
            Artifact("a".repeat(32), "first", "z-photo.png", "image/png", 100, "0".repeat(64), 2.0, "upload"),
            Artifact("b".repeat(32), "second", "a-report.pdf", "application/pdf", 200, "0".repeat(64), 1.0, "agent"))
        var source: String? = null
        compose.setContent { HermesTheme { Surface {
            Column { AttachmentBrowser(items, sessions, null, {}, {}, { source = it }) }
        } } }
        fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("z-photo.png") < top("a-report.pdf"))
        for (order in listOf("Name", "Type", "Size")) {
            compose.onNodeWithText(order).performClick()
            assertTrue(top("a-report.pdf") < top("z-photo.png"))
        }
        compose.onNodeWithText("All conversations ▾").performClick()
        compose.onAllNodesWithText("Second source").onLast().performClick()
        compose.onNodeWithText("z-photo.png").assertDoesNotExist()
        compose.onNodeWithText("Second source").performClick()
        assertEquals("second", source)
    }
    private fun previewHeader(width: Int, centered: Boolean) {
        var camera = androidx.compose.ui.geometry.Rect.Zero
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val left = if (centered) width / 2f - 21 else width - 50f
            camera = androidx.compose.ui.geometry.Rect(left * density, 0f,
                (if (centered) left + 42 else width.toFloat()) * density, 58 * density)
            androidx.compose.runtime.CompositionLocalProvider(LocalWindowCutouts provides listOf(camera), LocalImmersiveHeaders provides true) {
                HermesTheme { Surface { Row(Modifier.fillMaxSize()) {
                    if (!centered) Spacer(Modifier.weight(1f))
                    Column(if (centered) Modifier.fillMaxWidth() else Modifier.width(360.dp)) {
                        ArtifactPreviewHeader("studio-plan.pdf") {}
                    }
                } } }
            }
        }
        compose.waitForIdle()
        assertFalse(compose.onNodeWithContentDescription("Close preview").fetchSemanticsNode().boundsInRoot.overlaps(camera))
        compose.onRoot().captureRoboImage()
    }
    @Test fun previewHeaderCenteredCamera() = previewHeader(393, true)
    @Test @Config(qualifiers = "w840dp-h900dp") fun previewHeaderCornerCamera() = previewHeader(840, false)
}
