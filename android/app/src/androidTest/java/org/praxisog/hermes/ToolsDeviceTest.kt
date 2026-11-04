package org.praxisog.hermes

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.File
import java.util.UUID

/** Uses only the disposable native Hermes fixture and the separate fixture APK. */
@RunWith(AndroidJUnit4::class)
class ToolsDeviceTest {
    private val ui = DeviceFixtureUi
    private lateinit var workspace: Phase3Workspace
    @Test fun gallerySearchDirectUploadAndChatAttachmentsWorkIndependently() = ui.withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = HermesClient("http://127.0.0.1:18662", { "native-attachment-fixture-key" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val created = client.createSession("Tools test ${UUID.randomUUID()}")
        assertTrue(created.toString(), created is HermesResult.Ok)
        val session = (created as HermesResult.Ok).value
        val id = "tools-fixture"
        val cache = SessionCache(context, id)
        assertTrue(cache.writeDraftFiles(emptyList()))
        val png = ArtifactSharing.temporary(context, id, "Preview.png")
        Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(65, 125, 90)) }.let { bitmap ->
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val pdf = ArtifactSharing.temporary(context, id, "Preview.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 220, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("Bellpull file preview", 20f, 90f, Paint().apply { color = Color.BLACK; textSize = 20f })
            document.finishPage(page); pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        (1..8).forEach { index ->
            val source = if (index % 2 == 0) png else pdf
            val mime = if (index % 2 == 0) "image/png" else "application/pdf"
            val file = DraftFile(UUID.randomUUID().toString().replace("-", ""), session.id, "Sample $index.${source.extension}", mime, source.length())
            assertTrue(client.uploadArtifact(source, file, {}, {}) is HermesResult.Ok)
        }
        val picked = ArtifactSharing.temporary(context, id, "Direct upload.txt").apply { writeText("Uploaded without running the agent.") }
        val uri = ArtifactSharing.uri(context, picked)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val monitor = ui.instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        try {
            ui.main { workspace = Phase3Workspace(context, id, scope, client, cache, filesSupported = true, modelsSupported = false, runAttachmentsSupported = true) }
            ui.awaitMain("Files did not load") { workspace.artifacts.count { it.sessionId == session.id } == 8 }
            runBlocking {
                for (item in workspace.artifacts.filter { it.sessionId == session.id }) assertNotNull(workspace.thumbnails.load(item, allowPdf = true))
            }
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                var preview by mutableStateOf<Artifact?>(null)
                val gpu = GpuTelemetry(1, 100.0, cards = listOf(GpuCard("one", "AMD", "0", "Fixture GPU", sampledAt = 100.0,
                    sensors = listOf(GpuSensor("edge", 48.0, 0.0, 110.0), GpuSensor("hotspot", 67.0, 0.0, 120.0)))))
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    CompositionLocalProvider(LocalArtifactThumbnails provides workspace.thumbnails) {
                        ToolsWorkspace { height ->
                            AttachmentLibrary(workspace.artifacts.filter { it.sessionId == session.id }, listOf(session), height, workspace.errors["artifacts"], workspace::refreshArtifacts,
                                { preview = it }, {}, upload = { ServerUploadButton(workspace, listOf(session), session.id) })
                            GpuPanel(gpu, null, 100.0)
                        }
                        preview?.let { item -> ArtifactPreviewDialog({ preview = null }) { ArtifactPreview(workspace, item) { preview = null } } }
                    }
                } } } }
                ui.await("Gallery absent") { ui.text("Sample 8.png") != null }
                ui.screenshot("tools-recent-gallery")
                ui.tapText("Sample 7.pdf")
                ui.await("PDF preview did not open") { ui.text("Save") != null }
                ui.screenshot("tools-pdf-preview")
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Close preview" }))
                ui.tapText("Upload files")
                ui.tapText("Choose files to upload")
                ui.awaitMain("Direct upload failed: ${workspace.errors}") { workspace.serverUploadedCount == 1 && !workspace.serverUploading }
                assertTrue(workspace.filesFor(session.id).isEmpty())
                ui.screenshot("tools-upload-complete")
                ui.tapText("Close")
                ui.tapText("Search")
                ui.await("Search field absent") { ui.find { it.isEditable } != null }
                assertTrue(ui.find { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Direct upload.txt")
                }))
                ui.await("Uploaded file absent from Search") { ui.text("Direct upload.txt") != null }
                ui.screenshot("tools-file-search")
                ui.tapText("Close")
                ui.await("GPU row did not return after closing Search") { ui.find { it.contentDescription?.toString() == "Fixture GPU sensor details" } != null }
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Fixture GPU sensor details" }))
                ui.await("GPU details absent") { ui.text("Hotspot") != null }
                ui.screenshot("tools-gpu-details")
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SessionDetailScreen(UiState.Ready(emptyList()), TurnState.Idle, { false }, {}, {},
                        composerLeading = { AttachButton(workspace, session.id) {} }, attachmentStrip = { AttachmentStrip(workspace, session.id) })
                } } } }
                ui.await("Chat attachment plus absent") { ui.find { it.contentDescription?.toString() == "Attach file or photo" } != null }
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Attach file or photo" }))
                ui.tapText("Choose files or images")
                ui.awaitMain("Chat attachment did not remain a draft") { workspace.filesFor(session.id).size == 1 }
                assertFalse(workspace.filesFor(session.id).single().uploaded)
                assertTrue(workspace.serverUploads.isEmpty())
            }
            assertTrue((client.messages(session.id) as HermesResult.Ok).value.data.isEmpty())
            val uploaded = (client.artifacts(session.id) as HermesResult.Ok).value.data
            assertEquals(9, uploaded.size)
            val direct = uploaded.single { it.name == "Direct upload.txt" }
            val downloaded = File(context.cacheDir, "verified-upload.txt")
            assertTrue(client.downloadArtifact(direct, downloaded, {}) is HermesResult.Ok)
            assertEquals(picked.readText(), downloaded.readText())
        } finally {
            ui.instrumentation.removeMonitor(monitor)
            if (::workspace.isInitialized) workspace.close()
            scope.cancel(); client.close()
        }
    }
}
