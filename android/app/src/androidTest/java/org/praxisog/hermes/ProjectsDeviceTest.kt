package org.praxisog.hermes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.net.HttpURLConnection
import java.net.URI

/** Compile without a phone; run only with the separate sandbox and --projects fixture. */
@RunWith(AndroidJUnit4::class)
class ProjectsDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "http://127.0.0.1:18646"
    private val key = "bellpull-stock-device-fixture"
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(check: () -> Boolean) {
        val until = System.nanoTime() + 25_000_000_000L
        var ready = false
        while (!ready && System.nanoTime() < until) { main { ready = check() }; if (!ready) Thread.sleep(50) }
        assertTrue("Project fixture condition timed out", ready)
    }
    private fun control(path: String, body: String = "{}"): JsonObject {
        val request = URI(origin + path).toURL().openConnection() as HttpURLConnection
        request.connectTimeout = 5000; request.readTimeout = 5000
        request.setRequestProperty("Authorization", "Bearer $key")
        request.setRequestProperty("Content-Type", "application/json")
        request.requestMethod = "POST"; request.doOutput = true
        request.outputStream.use { it.write(body.toByteArray()) }
        return try {
            assertTrue(request.responseCode in 200..299)
            request.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        } finally { request.disconnect() }
    }

    @Test fun createMovePinStartBranchAndCompressionSurviveEncryptedCacheRestart() {
        check(context.packageName == "org.praxisog.hermes.fixture") { "Use the separate device fixture application ID" }
        assertEquals("0.4.0", installedAppVersion(context).name)
        val secure = SecureConnectionStore(context)
        main { BellpullRuntime.get(context).disconnect() }
        secure.clear()
        val profile = secure.add("Projects fixture", origin, key)
        secure.activate(profile.id)
        var cache = SessionCache(context, profile.id)
        val client = HermesClient(origin, { key })
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        assertTrue(capabilities.features["bellpull_projects"] == true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var workspace = ProjectWorkspace(scope, client, cache, true)
        val project = ConversationProject("d".repeat(32), "Costumes", "Finish before October 31", "#59091E", "craft")
        try {
            main { workspace.refresh() }
            await { workspace.canEdit }
            main { workspace.save(project, true) {} }
            await { workspace.canEdit && workspace.snapshot.projects.any { it.id == project.id } }
            for (session in listOf("phone_cli", "phone_tui")) {
                main { workspace.move(session, project.id) {} }
                await { workspace.canEdit && workspace.snapshot.projectFor(session) == project.id }
            }
            main { workspace.pin("phone_cli", true) }
            await { workspace.canEdit && workspace.snapshot.membership("phone_cli")?.pinned == true }
            var created: Session? = null
            main {
                workspace.choose(project.id)
                val request = workspace.creationRequest("Project chat", null)
                scope.launch { created = (request() as HermesResult.Ok).value }
            }
            await { created != null && workspace.canEdit }
            assertEquals(project.id, workspace.snapshot.projectFor(created!!.id))
            control("/fixture/projects/${created!!.id}/fork", """{"id":"phone_project_branch"}""")
            val continuation = control("/fixture/projects/phone_cli/compact")["session_id"]!!.jsonPrimitive.content
            main { workspace.refresh() }
            await { workspace.canEdit && workspace.snapshot.projectFor("phone_project_branch") == project.id &&
                workspace.snapshot.projectFor(continuation) == project.id }
            main {
                cache.writeDraft("phone_tui", androidx.compose.ui.text.input.TextFieldValue("Keep this draft"))
                workspace.close(); cache.close()
                cache = SessionCache(context, profile.id)
                workspace = ProjectWorkspace(scope, client, cache, true)
            }
            assertEquals(project.id, workspace.selectedScope)
            assertEquals("Finish before October 31", workspace.selectedProject!!.description)
            assertTrue(workspace.snapshot.membership(continuation)!!.pinned)
            assertFalse(workspace.snapshot.membership("phone_project_branch")!!.pinned)
            assertEquals("Keep this draft", cache.readDrafts()["phone_tui"]!!.text)
            assertEquals(4, workspace.snapshot.memberships.count { it.projectId == project.id })
            val other = SessionCache(context, "other_project_fixture")
            try { assertNull(other.readProjects()); assertEquals(ALL_PROJECT_CHATS, other.projectScope()) }
            finally { other.close() }
            cache.writeCapabilities(capabilities.raw.toString())
            cache.rememberSession(null) // Opening the fixture UI starts in the remembered project view.
        } finally {
            main { workspace.close(); cache.close(); scope.cancel() }
            client.close()
        }
    }
}
