package org.praxisog.hermes

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ProjectWorkspaceTest {
    private val wireJson = Json { encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val one = ConversationProject("1".repeat(32), "Costumes")
    private val two = ConversationProject("2".repeat(32), "Reference", archived = true)
    private val server = ProjectSnapshot(revision = 4, projects = listOf(one, two), memberships = listOf(
        ProjectMembership("root", listOf("root", "tip"), one.id, pinned = true), ProjectMembership("branch", projectId = two.id)))
    private class Cache(var snapshot: ProjectSnapshot?, var selected: String = ALL_PROJECT_CHATS) : ProjectCache {
        var pending: PendingProjectChat? = null
        override fun readProjects() = snapshot
        override fun writeProjects(value: ProjectSnapshot): Boolean { snapshot = value; return true }
        override fun projectScope() = selected
        override fun rememberProjectScope(value: String) { selected = value }
        override fun pendingProjectChat() = pending
        override fun writePendingProjectChat(value: PendingProjectChat?): Boolean { pending = value; return true }
    }
    @After fun close() { scope.cancel() }
    private fun client(handler: (Request) -> Pair<Int, String>): HermesClient = HermesClient("https://fixture.invalid", { "fixture" },
        httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
    private fun waitUntil(test: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000
        while (!test()) { if (System.nanoTime() > until) error("Timed out"); Thread.sleep(10) }
    }

    @Test fun cacheSupportsNavigationAliasesIndicatorsAndIndependentProfilesWithoutWrites() {
        val requests = CopyOnWriteArrayList<String>()
        val client = client { requests += it.url.encodedPath; 200 to wireJson.encodeToString(server) }
        val cache = Cache(server, one.id)
        val workspace = ProjectWorkspace(scope, client, cache, supported = false)
        val all = listOf(Session("tip"), Session("branch"), Session("unfiled"))
        assertEquals(one.id, workspace.selectedScope)
        assertEquals(listOf("tip"), workspace.sessions(all).map { it.id })
        assertEquals(setOf("tip"), workspace.unreadIds(all, setOf("root")))
        assertEquals(mapOf("tip" to "Running"), workspace.runLabels(all, mapOf("root" to "Running")))
        assertEquals(1 to 1, workspace.projectIndicators(one.id, setOf("root", "tip"), setOf("root")))
        workspace.choose(UNFILED_PROJECT_CHATS)
        assertEquals(listOf("unfiled"), workspace.sessions(all).map { it.id })
        workspace.choose(ALL_PROJECT_CHATS)
        assertEquals(all, workspace.sessions(all)) // Archived chats stay in All.
        assertEquals(ALL_PROJECT_CHATS, cache.selected)
        assertFalse(workspace.canEdit)
        workspace.move("root", null) {}
        assertTrue(requests.isEmpty())
        assertTrue(ProjectWorkspace(scope, client, Cache(null), false).snapshot.projects.isEmpty())
        workspace.close(); client.close()
    }

    @Test fun notificationFolderSeparatesBackgroundSourcesAndUnreadWithoutMovingHistory() {
        val client = client { 200 to wireJson.encodeToString(server) }
        val cache = Cache(server)
        val workspace = ProjectWorkspace(scope, client, cache, true, backgroundFolders = true)
        val all = listOf(Session("tip", source = "api_server"), Session("manual", source = "cli"),
            Session("scheduled", source = "cron"), Session("alert", source = "bellpull_notification"))
        try {
            assertEquals(listOf("tip", "manual"), workspace.sessions(all).map { it.id })
            assertEquals(setOf("tip"), workspace.unreadIds(workspace.sessions(all), setOf("root", "scheduled")))
            workspace.choose(NOTIFICATION_PROJECT_CHATS)
            assertEquals(listOf("scheduled", "alert"), workspace.sessions(all).map { it.id })
            workspace.refresh(); waitUntil { workspace.fresh }
            assertEquals(NOTIFICATION_PROJECT_CHATS, workspace.selectedScope)
            workspace.choose(ALL_WITH_NOTIFICATIONS); assertEquals(all, workspace.sessions(all))
            workspace.choose(UNFILED_PROJECT_CHATS)
            assertEquals(listOf("manual"), workspace.sessions(all).map { it.id })
            assertEquals(server.memberships, workspace.snapshot.memberships)
        } finally { workspace.close(); client.close() }
    }

    @Test fun globalPinCapabilityControlsUnfiledWritesAndAllScopesSeeOnePinState() {
        val pinned = server.copy(memberships = server.memberships + ProjectMembership("unfiled", pinned = true))
        val requests = CopyOnWriteArrayList<String>()
        val client = client { requests += it.method; 200 to wireJson.encodeToString(pinned) }
        val workspace = ProjectWorkspace(scope, client, Cache(pinned), true)
        try {
            workspace.refresh(); waitUntil { workspace.fresh }
            assertFalse(workspace.canPin("unfiled")); assertTrue(workspace.canPin("root"))
            workspace.pin("unfiled", false); assertEquals(listOf("GET"), requests)
            workspace.configure(true, true); assertTrue(workspace.canPin("unfiled"))
            val all = listOf(Session("tip"), Session("unfiled"))
            assertEquals(setOf("tip", "unfiled"), workspace.pinnedIds(all))
            workspace.choose(UNFILED_PROJECT_CHATS)
            assertEquals(setOf("unfiled"), workspace.pinnedIds(workspace.sessions(all)))
            assertTrue(ProjectWorkspace(scope, client, Cache(null), false).pinnedIds(all).isEmpty())
        } finally { workspace.close(); client.close() }
    }
    @Test fun staleMutationRefreshesWithoutOptimisticallyMovingTheChatOrLosingScope() {
        val latest = server.copy(revision = 5)
        val client = client { request -> if (request.method == "POST") 409 to
            """{"error":{"message":"Projects changed on another client","code":"project_revision_conflict"}}"""
            else 200 to wireJson.encodeToString(latest) }
        val workspace = ProjectWorkspace(scope, client, Cache(server, one.id), true)
        workspace.refresh(); waitUntil { workspace.fresh }
        workspace.move("tip", null) {}
        waitUntil { !workspace.mutating && workspace.error != null }
        assertEquals(one.id, workspace.snapshot.projectFor("tip"))
        assertEquals(one.id, workspace.selectedScope)
        assertEquals(5L, workspace.snapshot.revision)
        assertEquals("Projects changed on another client", workspace.error)
        workspace.close(); client.close()
    }

    @Test fun uncertainProjectChatCreationSurvivesRestartAndRetriesTheSameId() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        var first = true
        val client = client { request ->
            if (request.method == "POST") {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                requests += buffer.readUtf8()
                if (first) { first = false; throw IOException("Lost response after server commit") }
                200 to """{"session":{"id":"${cachePendingId(requests.first())}"}}"""
            } else 200 to wireJson.encodeToString(server)
        }
        val cache = Cache(server, one.id)
        var workspace = ProjectWorkspace(scope, client, cache, true)
        workspace.refresh(); waitUntil { workspace.fresh }
        val captured = workspace.creationRequest("Plan", null)
        workspace.choose(UNFILED_PROJECT_CHATS)
        assertTrue(captured() is HermesResult.Err)
        val savedId = cache.pending!!.id
        assertEquals(one.id, cache.pending!!.projectId) // Captured folder survives navigation.
        workspace.close()
        workspace = ProjectWorkspace(scope, client, cache, true)
        workspace.refresh(); waitUntil { workspace.fresh }
        assertEquals(savedId, (workspace.retryPending() as HermesResult.Ok).value.id)
        assertEquals(requests.first(), requests.last())
        assertEquals(2, requests.size)
        assertNull(cache.pending)
        workspace.close(); client.close()
    }

    private fun cachePendingId(raw: String) = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        .let { it as kotlinx.serialization.json.JsonObject }.getValue("id").let { it as kotlinx.serialization.json.JsonPrimitive }.content

    @Test fun switchingProjectScopePreservesDraftsAndAdmittedRunOwnership() {
        val client = client { request -> 200 to if (request.url.encodedPath.endsWith("/messages"))
            """{"session_id":"root","data":[]}""" else """{"data":[]}""" }
        val drafts = ChatDrafts()
        drafts.set("root", androidx.compose.ui.text.input.TextFieldValue("Unsent draft"))
        val saved = RunSubmission("once", "root", "Already admitted", 1.0, 1, emptySet(), runId = "run", status = "completed", historyNeedsReview = true)
        val journal = object : RunJournal {
            override fun readRuns() = listOf(saved)
            override fun writeRun(value: RunSubmission) = true
        }
        val store = HermesStore(scope, client, drafts = drafts, durableRetention = 86400, journal = journal)
        store.startRecovery(); store.openSession("root")
        val workspace = ProjectWorkspace(scope, client, Cache(server), false)
        workspace.choose(one.id); workspace.choose(two.id)
        assertEquals("Unsent draft", drafts["root"].text)
        assertEquals("root", store.openSessionId)
        assertEquals("run", store.runs!!.states["root"]!!.submission.runId)
        assertTrue(store.runs.states["root"]!!.busy)
        store.close(); workspace.close(); client.close()
    }
}
