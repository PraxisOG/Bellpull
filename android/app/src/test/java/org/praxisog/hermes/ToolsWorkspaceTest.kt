package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ToolsWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private val files = (1..12).map { Artifact(it.toString(16).padStart(32, '0'), "chat", "Report $it.pdf",
        "application/pdf", it * 1024L, "digest", it.toDouble(), "agent") }
    private val gpu = GpuTelemetry(1, 100.0, cards = (0..1).map { GpuCard("gpu-$it", "AMD", "$it", "AMD GPU $it",
        sampledAt = 100.0, sensors = listOf(GpuSensor("edge", 45.0 + it, 0.0, 110.0), GpuSensor("hotspot", 72.0, 0.0, 120.0))) })
    private fun render(onPreview: (Artifact) -> Unit = {}) {
        compose.setContent { HermesTheme { Surface {
            ToolsWorkspace { height ->
                AttachmentLibrary(files, listOf(Session("chat", title = "Research")), height, null, {}, onPreview, {},
                    upload = { TextButton(onClick = {}) { Text("Upload files") } })
                GpuPanel(gpu, null, 100.0)
            }
        } } }
    }
    @Test fun recentFilesAreBoundedDeduplicatedAndNewestFirst() {
        val items = recentArtifacts(files + files + files.last().copy(id = "deleted", createdAt = 50.0, deleted = true))
        assertEquals(listOf(12,11,10,9,8,7,6,5).map { "Report $it.pdf" }, items.map { it.name })
    }
    @Test fun coverTools() { render(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun unfoldedTools() { render(); compose.onRoot().captureRoboImage() }
    @Test fun galleryOpensFilesAndSearchFindsOlderFiles() {
        var viewed: Artifact? = null
        render { viewed = it }
        compose.onNodeWithText("Search apps & chats").assertDoesNotExist()
        compose.onNodeWithText("Session info").assertDoesNotExist()
        compose.onNodeWithText("Attachments").assertDoesNotExist()
        compose.onNodeWithText("Report 12.pdf").performClick()
        assertEquals(files.last(), viewed)
        compose.onNodeWithText("Report 1.pdf").assertDoesNotExist()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Search files").performTextInput("Report 1.pdf")
        compose.onNodeWithTag("library-file-${files.first().id}").assertIsDisplayed().performClick()
        assertEquals(files.first(), viewed)
        compose.onNodeWithText("Search files").assertDoesNotExist()
    }
    @Test fun searchCanBeClearedAndDismissedAndHasAnEmptyState() {
        render()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Search files").performTextInput("missing file")
        compose.onNodeWithText("No matching files.").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithTag("library-file-${files.last().id}").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Search files").assertDoesNotExist()
    }
    @Test fun loadingFailureAndEmptyGalleryRemainDistinct() {
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        compose.setContent { HermesTheme { AttachmentLibrary(emptyList(), emptyList(), 360.dp, error, {}, {}, {}, loading = loading) } }
        compose.onNodeWithText("Loading files…").assertIsDisplayed()
        compose.runOnIdle { loading = false; error = "Server offline" }
        compose.onNodeWithText("Files could not be loaded. Open Search to retry.").assertIsDisplayed()
        compose.onNodeWithText("Server offline").assertIsDisplayed()
        compose.runOnIdle { error = null }
        compose.onNodeWithText("No files yet.").assertIsDisplayed()
    }
    @Test fun gpuDetailsExpandWithoutHidingStalenessOrMissingReadings() {
        val card = gpu.cards.first().copy(stale = true)
        compose.setContent { HermesTheme { GpuPanel(gpu.copy(cards = listOf(card)), null, 100.0) } }
        compose.onNodeWithText("Hotspot").assertDoesNotExist()
        compose.onNodeWithText("Stale").assertIsDisplayed()
        compose.onNodeWithContentDescription("AMD GPU 0 sensor details").performClick()
        compose.onNodeWithText("Hotspot").assertIsDisplayed()
        compose.onNodeWithContentDescription("AMD GPU 0 sensor details").performClick()
        compose.onNodeWithText("Hotspot").assertDoesNotExist()
        assertEquals("edge", primaryGpuSensor(card)?.name)
        assertEquals("hotspot", primaryGpuSensor(card.copy(sensors = card.sensors.map { if (it.name == "edge") it.copy(celsius = Double.NaN) else it }))?.name)
        assertNull(primaryGpuSensor(card.copy(sensors = emptyList())))
    }
}
