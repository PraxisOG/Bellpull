package org.praxisog.hermes

import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

/** Only the disposable package and tools/mobile-bugs-device-fixture.py. */
class MobileBugsDeviceTest {
    @Test fun shareIntoChatUploadsBothFilesAndShowsNativeSvgPreview() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = BellpullRuntime.get(context)
        ui.main { runtime.disconnect() }
        val secure = SecureConnectionStore(context)
        secure.clear()
        val profile = secure.add("Local mobile repair fixture", "http://127.0.0.1:18662", "native-attachment-fixture-key")
        secure.activate(profile.id)
        val folder = "Download/Bellpull-fixtures/mobile-bugs-${System.nanoTime()}"
        val uris = mutableListOf<Uri>()
        fun insert(name: String, mime: String, contents: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, folder); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            uris += uri
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(contents.toByteArray()) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            val svg = insert("diagram.svg", "image/svg+xml", """<svg xmlns="http://www.w3.org/2000/svg" width="480" height="240"><rect width="480" height="240" fill="#59091e"/><text x="240" y="130" text-anchor="middle" fill="white" font-size="32">Local Hermes · SVG received</text></svg>""")
            val text = insert("notes.txt", "text/plain", "Synthetic attachment from the isolated phone fixture.")
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE; type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(svg, text))
                putExtra(Intent.EXTRA_TEXT, "Review the fixture attachments")
                clipData = ClipData.newUri(context.contentResolver, "diagram.svg", svg).apply {
                    addItem(ClipData.Item(text))
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                ui.awaitMain("Attachment support did not load") { runtime.phase3?.runAttachmentsSupported == true }
                ui.tapText("New conversation")
                ui.awaitMain("Shared files did not enter the composer") {
                    runtime.phase3?.drafts?.size == 2 && runtime.store?.openSessionId != null
                }
                val session = checkNotNull(runtime.store?.openSessionId)
                ui.awaitMain("Attachment model selection did not load") { runtime.phase3?.selections?.containsKey(session) == true }
                assertTrue(runtime.phase3!!.runAttachmentsSupported)
                assertTrue(runtime.store!!.runs!!.states.isEmpty())
                ui.screenshot("mobile-bugs-attachment-draft")
                ui.await("Send action missing") { ui.find { it.contentDescription?.toString() == "Send" || it.text?.toString() == "Send" } != null }
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Send" || it.text?.toString() == "Send" }))
                ui.awaitMain("Attachment run did not complete and reconcile") {
                    runtime.store?.runs?.states?.get(session)?.submission?.historySynced == true
                }
                ui.awaitMain("Generated file did not appear") { runtime.phase3?.artifacts?.any { it.name == "result.txt" } == true }
                assertTrue(runtime.phase3!!.drafts.isEmpty())
                assertEquals("", runtime.drafts[session].text)
                assertEquals(1, (runtime.store!!.history as UiState.Ready).value.count { it.role == "user" && it.content == "Review the fixture attachments" })
                ui.await("SVG attachment missing from transcript") { ui.find { it.contentDescription?.toString() == "View diagram.svg" } != null }
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "View diagram.svg" }))
                ui.await("Native SVG bitmap did not render") { ui.find { it.contentDescription?.toString() == "diagram.svg" } != null }
                ui.screenshot("mobile-bugs-native-svg-preview")
                ui.tapText("+")
                ui.tapText("Reset zoom")
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Close preview" }))
            }
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
            ui.main { runtime.disconnect() }
        }
    }
}
