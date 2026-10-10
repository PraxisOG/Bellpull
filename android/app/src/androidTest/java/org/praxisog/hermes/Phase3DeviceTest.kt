package org.praxisog.hermes

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

/** Dedicated emulator only. Uses a disposable fixture server and real MediaStore URIs. */
@RunWith(AndroidJUnit4::class)
class Phase3DeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val fixtureFolder = "Download/Bellpull-fixtures/${System.nanoTime()}"
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)

    @Test fun contentProviderUploadRetryPreviewAndShareDraft() {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Run Phase3DeviceTest only on a dedicated emulator" }
        val runtime = BellpullRuntime.get(context)
        main { runtime.disconnect() }
        val secure = SecureConnectionStore(context)
        secure.clear()
        val profile = secure.add("Phase 3 fixture", "http://127.0.0.1:18645", "bellpull-emulator-fixture-key")
        secure.activate(profile.id)
        val client = HermesClient(profile.url, { "bellpull-emulator-fixture-key" })
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        main { runtime.prepare(profile.id); runtime.connect(client, capabilities) }
        val image = insert("fixture-photo.png", "image/png") { out ->
            val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(100, 30, 55)); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }
        val pdf = insert("retry-fixture.pdf", "application/pdf") { out ->
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText("Bellpull fixture page ${index + 1}", 20f, 50f, Paint().apply { color = Color.BLACK; textSize = 16f })
                    document.finishPage(page)
                }
                document.writeTo(out)
            } finally { document.close() }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE; type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(image, pdf))
            putExtra(Intent.EXTRA_TEXT, "Review these two fixture files")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Add to a draft").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("New conversation").performClick()
            compose.waitUntil(20_000) { runtime.phase3?.drafts?.size == 2 && runtime.store?.openSessionId != null }
            val session = runtime.store!!.openSessionId!!
            val ids = runtime.phase3!!.drafts.map { it.id }
            assertTrue(runtime.store!!.runs!!.states.isEmpty()) // Share-in never auto-submits.
            assertEquals("Review these two fixture files", runtime.drafts[session].text)
            scenario.recreate()
            compose.onNodeWithText("Add to a draft").assertDoesNotExist()
            assertEquals(ids, runtime.phase3!!.drafts.map { it.id })
            compose.waitUntil(10_000) { runtime.phase3?.selections?.containsKey(session) == true }
            compose.onNodeWithText("Send", useUnmergedTree = true).performClick()
            compose.waitUntil(20_000) { runtime.phase3?.errors?.get(session) == "Hermes returned HTTP 503." }
            assertEquals(ids, runtime.phase3!!.drafts.map { it.id })
            assertEquals("Review these two fixture files", runtime.drafts[session].text)
            compose.onNodeWithText("Send", useUnmergedTree = true).performClick()
            compose.waitUntil(25_000) { runtime.store?.runs?.states?.get(session)?.submission?.historySynced == true }
            compose.waitUntil(15_000) { runtime.phase3?.artifacts?.any { it.name == "generated-report.pdf" } == true }
            assertTrue(runtime.phase3!!.drafts.isEmpty())
            assertTrue(runtime.drafts[session].text.isEmpty())
            compose.waitUntil(10_000) { compose.onAllNodesWithText("retry-fixture.pdf").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("retry-fixture.pdf").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("›").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Close preview").performClick()
            compose.onNodeWithText("fixture-photo.png").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("fixture-photo.png").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Close preview").performClick()
            // Open a generated PDF from the same artifact records.
            compose.onNodeWithText("generated-report.pdf").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Share", useUnmergedTree = true).assertIsEnabled()
            compose.onNodeWithContentDescription("Close preview").performClick()
        }
        context.contentResolver.delete(image, null, null); context.contentResolver.delete(pdf, null, null)
        // Keep this synthetic profile/chat for manual picker and Android Sharesheet inspection.
    }

    private fun insert(name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, fixtureFolder); put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use(write)
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }
}
