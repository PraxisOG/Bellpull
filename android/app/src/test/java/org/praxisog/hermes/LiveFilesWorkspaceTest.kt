package org.praxisog.hermes

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.IOException

class LiveFilesWorkspaceTest {
    private val id = "a".repeat(32)
    private val etag = "\"" + "b".repeat(64) + "\""
    private val snapshot = LiveFileSnapshot(id = id, text = "- [ ] Milk\n", etag = etag, access = "checklist", tasks = listOf(LiveFileTask(0, "Milk", false)))
    private suspend fun await(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(5) }
    private fun response(request: Request, text: String, status: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(text.toResponseBody("application/json".toMediaType())).build()
    @Test fun lostEditResponseRetainsIdentityAcrossWorkspaceRestartAndRefreshUsesEtag() = runBlocking {
        var pending: PendingFileEdit? = null
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        var conditional = false
        val client = HermesClient("http://127.0.0.1:1", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor {
            val request = it.request()
            assertEquals("Bearer fixture", request.header("Authorization"))
            when {
                request.url.encodedPath.endsWith("/edit") -> {
                    requests.add(Buffer().also { buffer -> request.body!!.writeTo(buffer) }.readUtf8())
                    if (requests.size == 1) throw IOException("Response lost after save")
                    response(request, Json.encodeToString(snapshot.copy(text = "- [ ] Milk\n- [ ] Eggs\n")))
                }
                request.url.encodedPath.endsWith(id) -> {
                    conditional = conditional || request.header("If-None-Match") == etag
                    response(request, Json.encodeToString(snapshot))
                }
                else -> response(request, Json.encodeToString(LiveFileList(files = listOf(LiveFile(id, "/list", "List")))))
            }
        }.build())
        val first = LiveFilesWorkspace(this, client, true, { pending }, { pending = it; true })
        var second: LiveFilesWorkspace? = null
        try {
            first.open(id); await { first.fresh && !first.loading }
            first.refresh(); await { !first.loading && conditional }
            assertTrue(first.add("Eggs")); await { !first.busy && first.problem != null }
            assertNotNull(pending); first.close()
            second = LiveFilesWorkspace(this, client, true, { pending }, { pending = it; true })
            second.retryEdit(); await { !second.busy && pending == null }
            assertEquals(2, requests.size); assertEquals(requests[0], requests[1])
        } finally { first.close(); second?.close(); client.close() }
    }
    @Test fun conflictAndFailedJournalKeepUserEditFromBeingOverwritten() = runBlocking {
        var saves = false; var attempts = 0
        val client = HermesClient("http://127.0.0.1:1", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor {
            val request = it.request()
            when {
                request.url.encodedPath.endsWith("/edit") -> { attempts++; response(request, """{"error":{"message":"File changed"}}""", 409) }
                request.url.encodedPath.endsWith(id) -> response(request, Json.encodeToString(snapshot))
                else -> response(request, """{"version":1,"files":[]}""")
            }
        }.build())
        val files = LiveFilesWorkspace(this, client, true, savePending = { saves })
        try {
            files.open(id); await { files.fresh && !files.loading }
            assertFalse(files.add("Eggs")); assertEquals(0, attempts)
            saves = true; assertTrue(files.add("Eggs")); await { !files.busy && attempts == 1 }
            assertNotNull(files.pending); assertFalse(files.add("Another item")); assertEquals(1, attempts)
            files.reviewEdit(); assertNull(files.pending)
        } finally { files.close(); client.close() }
    }
    @Test fun sharedPathRefreshUsesConditionalReadAndClearsRevokedContent() = runBlocking<Unit> {
        var allowed = true
        var conditional = false
        val path = "work/notes #1.txt"
        val client = HermesClient("http://127.0.0.1", { "synthetic" }, httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.queryParameter("path") == null) {
                response(request, """{"files":[],"path_roots":["work"]}""")
            } else {
                assertEquals(path, request.url.queryParameter("path"))
                conditional = request.header("If-None-Match") == etag
                val reply = response(request, """{"id":"$id","source_path":"$path","access":"read","etag":${JsonPrimitive(etag)},"text":"hello"}""")
                when { !allowed -> reply.newBuilder().code(404).build(); conditional -> reply.newBuilder().code(304).build(); else -> reply }
            }
        }.build())
        val files = LiveFilesWorkspace(this, client, true)
        try {
            files.openPath(path); await { files.fresh && !files.loading }
            assertEquals("hello", files.snapshot?.text); assertFalse(files.add("changed"))
            files.refresh(); await { conditional && !files.loading }
            assertEquals("hello", files.snapshot?.text)
            allowed = false; files.refresh(); await { files.problem != null && !files.loading }
            assertNull(files.snapshot)
        } finally { files.close(); client.close() }
    }

}
