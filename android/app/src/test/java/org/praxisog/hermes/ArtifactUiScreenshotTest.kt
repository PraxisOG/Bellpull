package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ArtifactUiScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val session = UiFixtures.sessions[0]
    private val pdf = Artifact("a".repeat(32), session.id, "studio-plan.pdf", "application/pdf", 130000,
        "0".repeat(64), 3.0, "agent")
    private val png = pdf.copy(id = "b".repeat(32), name = "maroon.png", mime = "image/png", createdAt = 4.0)
    private val messages = listOf(Message(1, role = "user", content = "Make a studio plan and a maroon swatch.", timestamp = 1.0),
        Message(2, role = "assistant", content = "Here are the plan and color sample.", timestamp = 2.0))
    private val bitmap by lazy {
        Bitmap.createBitmap(320, 700, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            val canvas = Canvas(this)
            val paint = Paint().apply { color = android.graphics.Color.rgb(92, 27, 44); textSize = 20f }
            canvas.drawText("Studio plan", 24f, 40f, paint)
            paint.textSize = 13f
            repeat(22) { canvas.drawText("Room ${it + 1} · lighting and layout", 24f, 82f + it * 26, paint) }
        }.asImageBitmap()
    }
    private fun content() {
        compose.setContent {
            var preview by remember { mutableStateOf<Artifact?>(null) }
            var docked by remember { mutableStateOf(false) }
            val view: (Artifact) -> Unit = { preview = it; docked = false }
            val pane: @Composable (Modifier) -> Unit = { modifier ->
                ArtifactPreviewContent(preview!!, bitmap, 0, 2, false, null, true, modifier,
                    {}, {}, {}, {}, if (docked) null else { { docked = true } }, { preview = null })
            }
            HermesApp(UiState.Ready(listOf(session)), UiState.Ready(messages), TurnState.Idle,
                session.id, session, UiFixtures.NOW, {}, {}, {}, {}, {}, {}, {},
                preferences = BellpullPreferences(palette = Palette.MAROON, reduceMotion = true),
                artifacts = listOf(pdf, png), onViewArtifact = view,
                previewPane = if (preview != null && docked) pane else null,
                previewDialog = if (preview != null && !docked) { { ArtifactPreviewDialog({ preview = null }) { pane(Modifier) } } } else null)
        }
        compose.waitForIdle()
    }
    private fun rows() {
        content()
        compose.onNodeWithContentDescription("Download maroon.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("View studio-plan.pdf").assertIsDisplayed()
        compose.onNodeWithTag("artifact-${pdf.id}").assert(hasAnyAncestor(hasTestTag("transcript")))
        compose.onNodeWithContentDescription("Image").assertIsDisplayed()
        compose.onNodeWithContentDescription("File folder").assertIsDisplayed()
        captureScreenRoboImage()
    }
    @Test fun inlineArtifactsCover() = rows()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun inlineArtifactsUnfoldedDark() = rows()
    private fun dialog() {
        content()
        compose.onNodeWithContentDescription("View studio-plan.pdf").performClick()
        compose.onNodeWithTag("artifact-preview-dialog").assertExists()
        listOf("Open", "Save", "Share", "Reset zoom", "Open in right drawer").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        captureScreenRoboImage()
        fun scrollOffset() = compose.onNodeWithTag("artifact-scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val before = scrollOffset()
        compose.onNodeWithTag("artifact-scroll").performTouchInput { swipeUp() }
        val after = scrollOffset()
        assertTrue("Preview should scroll", after > before)
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithText("Reset zoom").performClick()
        compose.onNodeWithContentDescription("Fit page").performClick()
        compose.onNodeWithContentDescription("Scroll page").assertIsDisplayed().performClick()
        compose.onNodeWithText("Open in right drawer").performClick()
        compose.onNodeWithTag("artifact-preview-dialog").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("View studio-plan.pdf").assertIsDisplayed()
    }
    @Test fun previewDialogCover() = dialog()
    @Test @Config(qualifiers = "w840dp-h900dp") fun previewDialogUnfolded() = dialog()
    @Test @Config(qualifiers = "w840dp-h900dp") fun optionalRightDrawer() {
        content()
        compose.onNodeWithContentDescription("View studio-plan.pdf").performClick()
        compose.onNodeWithText("Open in right drawer").performClick()
        compose.onNodeWithTag("artifact-preview-dialog").assertDoesNotExist()
        compose.onNodeWithContentDescription("View maroon.png").assertIsDisplayed()
        captureScreenRoboImage()
    }
    @Test fun scrollingFilesOutOfViewDoesNotLeaveAComposerShelf() {
        val longHistory = (1..30).map { Message(it, role = if (it % 2 == 0) "assistant" else "user",
            content = "Message $it", timestamp = it.toDouble()) }
        var read: Int? = null
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(longHistory), TurnState.Idle, {}, {}, {},
                artifacts = listOf(pdf.copy(createdAt = 31.0)), onReadThrough = { read = it })
        } }
        compose.onNodeWithContentDescription("View studio-plan.pdf").assertIsDisplayed()
        assertEquals(30, read)
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        compose.onNodeWithTag("transcript").performScrollToIndex(0)
        compose.onNodeWithContentDescription("View studio-plan.pdf").assertDoesNotExist()
        compose.onNodeWithText("Message Hermes").assertIsDisplayed()
    }
    @Test fun artifactTailDoesNotPreventReadReceipts() {
        var read: Int? = null
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(messages), TurnState.Idle, {}, {}, {},
                artifacts = (1..30).map { pdf.copy(id = "file-$it", createdAt = 3.0 + it) },
                onReadThrough = { read = it })
        } }
        compose.waitForIdle()
        assertEquals(2, read)
    }
    @Test fun actionsIdentifyTheExactArtifact() {
        var viewed: Artifact? = null
        var downloaded: Artifact? = null
        compose.setContent { HermesTheme { Surface { Column {
            ArtifactRow(pdf, { downloaded = it }, { viewed = it })
            ArtifactRow(png, { downloaded = it }, { viewed = it })
        } } } }
        compose.onNodeWithContentDescription("Download maroon.png").performClick()
        assertEquals(png, downloaded); assertNull(viewed)
        compose.onNodeWithContentDescription("View studio-plan.pdf").performClick()
        assertEquals(pdf, viewed)
    }
    @Test fun missingArtifactStillExplainsItsState() {
        compose.setContent { HermesTheme { ArtifactRow(pdf.copy(available = false), {}, {}) } }
        compose.onNodeWithText("Missing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Download studio-plan.pdf").assertIsNotEnabled()
        compose.onNodeWithContentDescription("View studio-plan.pdf").assertIsEnabled()
    }
}
