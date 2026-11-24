package org.praxisog.hermes

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ServerUploadTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: HermesClient
    private lateinit var workspace: Phase3Workspace
    private lateinit var cache: SessionCache
    private val uploads = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val upload = DraftFile("a".repeat(32), "destination", "notes.txt", "text/plain", 5, uploadOnly = true)
    private val chat = DraftFile("b".repeat(32), "destination", "chat.txt", "text/plain", 5)
    private fun setup(status: () -> Int = { 200 }, gate: CountDownLatch? = null) {
        val context = RuntimeEnvironment.getApplication() as Context
        val id = "standalone-upload"
        cache = SessionCache(context, id, context.getSharedPreferences(id, Context.MODE_PRIVATE))
        assertTrue(cache.writeDraftFiles(listOf(upload, chat)))
        val root = File(context.filesDir, "bellpull-files/$id").apply { mkdirs() }
        listOf(upload, chat).forEach { File(root, it.id).writeText("hello") }
        val artifact = Artifact(upload.id, upload.sessionId, upload.name, upload.mime, upload.size, "digest", 1.0, "upload")
        client = HermesClient("http://fixture.invalid", { "synthetic" }, httpClient = HermesClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val request = chain.request(); requests.add("${request.method} ${request.url.encodedPath}")
                var code = 200
                val body = when (request.method) {
                    "PUT" -> {
                        uploads.add(request.url.toString())
                        assertEquals("hello", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                        gate?.await(10, TimeUnit.SECONDS)
                        code = status()
                        if (code == 200) Json.encodeToString(artifact) else """{"error":{"message":"Storage full","code":"quota"}}"""
                    }
                    "GET" -> Json.encodeToString(ArtifactList(if (uploads.isEmpty()) emptyList() else listOf(artifact)))
                    else -> error("Unexpected request: $request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
        workspace = Phase3Workspace(context, id, scope, client, cache, filesSupported = true, modelsSupported = false,
            runAttachmentsSupported = false)
        compose.setContent { HermesTheme { ServerUploadButton(workspace, listOf(Session("destination", title = "Research")), "destination") } }
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(5_000) { compose.runOnIdle(check) }
    @After fun close() {
        if (::workspace.isInitialized) workspace.close()
        scope.cancel()
        if (::client.isInitialized) client.close()
    }
    @Test fun standaloneUploadPreservesChatDraftAndNeverStartsARun() {
        setup()
        assertEquals(listOf(chat), workspace.filesFor("destination"))
        compose.runOnIdle { workspace.uploadServerFiles() }
        await { !workspace.serverUploading && workspace.serverUploads.isEmpty() }
        assertEquals(listOf(chat), cache.draftFiles())
        assertEquals(listOf(chat), workspace.filesFor("destination"))
        assertEquals(1, uploads.size)
        assertTrue(uploads.single().contains("session_id=destination"))
        assertTrue(requests.all { it.startsWith("GET /api/bellpull/artifacts") || it.startsWith("PUT /api/bellpull/artifacts/") })
        assertFalse(File(workspace.root, upload.id).exists()); assertTrue(File(workspace.root, chat.id).exists())
    }
    @Test fun failureKeepsIdentityAndFilesForRetry() {
        var code = 507
        setup({ code })
        compose.runOnIdle { workspace.uploadServerFiles() }
        await { !workspace.serverUploading && workspace.errors["uploads"] != null }
        assertEquals(listOf(upload), workspace.serverUploads)
        assertTrue(File(workspace.root, upload.id).exists())
        assertEquals(listOf(upload, chat), cache.draftFiles())
        code = 200
        compose.runOnIdle { workspace.uploadServerFiles() }
        await { !workspace.serverUploading && workspace.serverUploads.isEmpty() }
        assertEquals(uploads[0], uploads[1])
        assertNull(workspace.errors["uploads"])
    }
    @Test fun cancellationRetainsQueueAndDoesNotDiscardChatAttachments() {
        val gate = CountDownLatch(1)
        setup(gate = gate)
        try {
            compose.runOnIdle { workspace.uploadServerFiles() }
            await { uploads.isNotEmpty() }
            compose.runOnIdle { workspace.cancelServerUploads() }
            gate.countDown()
            await { !workspace.serverUploading }
            assertEquals(listOf(upload, chat), cache.draftFiles())
            assertEquals(listOf(upload), workspace.serverUploads)
            assertTrue(File(workspace.root, upload.id).exists())
            compose.runOnIdle { workspace.uploadServerFiles() }
            await { workspace.serverUploads.isEmpty() && !workspace.serverUploading }
            assertEquals(2, uploads.size)
        } finally { gate.countDown() }
    }
    @Test fun savedQueueCanBeReopenedAndRemovedWithoutUploading() {
        setup()
        compose.onNodeWithText("Uploads (1)").performClick()
        compose.onNodeWithText("Upload to server").assertIsDisplayed()
        compose.onAllNodesWithText("Research").assertCountEquals(2)
        compose.onNodeWithText("Retry uploads").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(chat), cache.draftFiles())
        assertTrue(uploads.isEmpty())
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Upload files").assertIsDisplayed()
    }
    @Test fun legacyDraftMetadataStillMeansChatAttachment() {
        val old = Json.decodeFromString<DraftFile>("""{"id":"old","sessionId":"chat","name":"notes.txt","mime":"text/plain","size":5}""")
        assertFalse(old.uploadOnly)
    }
    @Test fun pickedFilesUploadWithoutRunAttachmentSupportAndCaptureTheirDestination() {
        setup()
        val source = File(workspace.root, "picked.txt").apply { writeText("hello") }
        compose.runOnIdle {
            workspace.remove(upload.id)
            workspace.addUris("another-conversation", listOf(android.net.Uri.fromFile(source)), uploadOnly = true)
        }
        await { (workspace.importing["uploads"] ?: 0) == 0 && !workspace.serverUploading && uploads.isNotEmpty() }
        assertTrue(uploads.single().contains("session_id=another-conversation"))
        assertEquals(listOf(chat), cache.draftFiles())
        assertTrue(workspace.filesFor("another-conversation").isEmpty())
        compose.runOnIdle { workspace.addUris("destination", listOf(android.net.Uri.fromFile(source))) }
        assertNotNull(workspace.errors["destination"])
        assertEquals(1, uploads.size)
    }
    @Test fun cancellingWhileAnotherBatchImportsDoesNotRestartTransfers() {
        setup()
        val uri = android.net.Uri.parse("content://fixture/picked.txt")
        val opened = CountDownLatch(1)
        val resumeImport = CountDownLatch(1)
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStreamSupplier(uri) {
            opened.countDown()
            check(resumeImport.await(10, TimeUnit.SECONDS))
            "hello".byteInputStream()
        }
        try {
            compose.runOnIdle { workspace.addUris("destination", listOf(uri), uploadOnly = true) }
            assertTrue("The picked file never opened", opened.await(5, TimeUnit.SECONDS))
            compose.runOnIdle {
                assertEquals(1, workspace.importing["uploads"])
                workspace.cancelServerUploads()
            }
        } finally {
            resumeImport.countDown()
        }
        await { (workspace.importing["uploads"] ?: 0) == 0 }
        assertFalse(workspace.serverUploading)
        assertTrue(uploads.isEmpty())
        assertEquals(2, workspace.serverUploads.size)
        assertEquals(listOf(chat), workspace.filesFor("destination"))
    }
}
