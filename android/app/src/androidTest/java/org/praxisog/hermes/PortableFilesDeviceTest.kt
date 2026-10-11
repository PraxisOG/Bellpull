package org.praxisog.hermes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.DraftFile
import java.io.File
import java.security.MessageDigest

/** Safe on a personal phone ONLY with -PbellpullDeviceFixture=true. */
@RunWith(AndroidJUnit4::class)
class PortableFilesDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun stockPluginDownloadsFourIdenticalPngs() {
        check(context.packageName == "org.praxisog.hermes.fixture") { "Use the separate device fixture application ID" }
        val client = HermesClient("http://127.0.0.1:18646", { "bellpull-stock-device-fixture" })
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        assertTrue(capabilities.features["bellpull_persistent_artifacts"] == true)
        assertFalse(capabilities.features["bellpull_run_attachments"] == true)
        val artifacts = (client.artifacts("phone_fixture") as HermesResult.Ok).value.data
        assertEquals(4, artifacts.size)
        val destination = File(context.filesDir, "portable-downloads").apply { mkdirs() }
        artifacts.forEach { item ->
            val file = File(destination, item.name)
            assertTrue(client.downloadArtifact(item, file, {}) is HermesResult.Ok)
            assertEquals(item.size, file.length())
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(item.sha256, hash)
        }
        val secure = SecureConnectionStore(context)
        secure.clear() // Only the explicitly guarded fixture sandbox.
        val profile = secure.add("Stock fixture", "http://127.0.0.1:18646", "bellpull-stock-device-fixture")
        secure.activate(profile.id)
        val draft = DraftFile("b".repeat(32), "phone_fixture", "remember.txt", "text/plain", 7)
        val localDraft = File(context.filesDir, "bellpull-files/${profile.id}/${draft.id}")
        localDraft.parentFile!!.mkdirs(); localDraft.writeText("keep me")
        SessionCache(context, profile.id).writeDraftFiles(listOf(draft))
        val runtime = BellpullRuntime.get(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runtime.disconnect(); runtime.prepare(profile.id); runtime.connect(client, capabilities)
            runtime.store!!.openSession("phone_fixture")
            runtime.store!!.updateDraft(androidx.compose.ui.text.input.TextFieldValue("keep my draft"))
            runtime.phase3!!.send(runtime.store!!, "keep my draft")
            assertTrue(runtime.phase3!!.errors["phone_fixture"]!!.contains("nothing was sent"))
            assertEquals(listOf(draft), runtime.phase3!!.drafts)
            assertEquals("keep my draft", runtime.store!!.draft.text)
            assertTrue(localDraft.exists())
        }
        val until = System.nanoTime() + 15_000_000_000L
        while (runtime.phase3?.artifacts?.size != 4 && System.nanoTime() < until) Thread.sleep(50)
        assertEquals(4, runtime.phase3?.artifacts?.size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runtime.cache!!.writeDraftFiles(emptyList()); localDraft.delete()
            runtime.disconnect()
        }
    }
}
