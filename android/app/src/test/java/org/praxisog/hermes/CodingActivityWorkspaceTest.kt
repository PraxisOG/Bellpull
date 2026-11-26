package org.praxisog.hermes

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CodingActivityWorkspaceTest {
    private val wire = Json { encodeDefaults = true }
    private fun snapshot(id: String, cursor: Long = 1) = CodingSnapshot(1,id,listOf(id),cursor,true,false,
        works=listOf(CodingWork("a".repeat(32),id,"run_one","proc_one",state="running")),
        rows=listOf(CodingRow(cursor,"a".repeat(32),"part",123,"text","Response","Observed $id")),observedAt=1.0)
    private class Cache : CodingCache {
        val values = mutableMapOf<String,CodingSnapshot>()
        override fun readCoding(id: String) = values[id]
        override fun writeCoding(value: CodingSnapshot) { values[value.sessionId] = value }
    }
    private fun waitUntil(test: () -> Boolean) {
        val deadline=System.nanoTime()+5_000_000_000
        while (!test()) { check(System.nanoTime()<deadline) { "Timed out" }; Thread.sleep(10) }
    }
    @Test fun failedFirstReadRetriesAndNavigationNeverAdmitsOrStopsARun() {
        val requests=CopyOnWriteArrayList<Request>(); val attempts=AtomicInteger()
        val client=HermesClient("https://fixture.invalid/p/work",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val request=chain.request().also(requests::add)
                if (attempts.incrementAndGet()==1) throw IOException("Initial connection failed")
                val id=request.url.pathSegments[request.url.pathSegments.size-2]
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(wire.encodeToString(snapshot(id)).toResponseBody("application/json".toMediaType())).build()
            }.build())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val cache=Cache(); val workspace=CodingActivityWorkspace(scope,client,cache,true,pollMs=10)
        try {
            workspace.observe("root",true)
            waitUntil { workspace.snapshot?.sessionId=="root" && !workspace.disconnected }
            assertTrue(attempts.get()>=2)
            workspace.observe("other",true); waitUntil { workspace.snapshot?.sessionId=="other" }
            workspace.observe("root",false)
            assertEquals("Observed root",workspace.snapshot!!.rows.single().detail)
            assertTrue(requests.all { it.method=="GET" && it.url.encodedPath.startsWith("/p/work/api/bellpull/") })
            workspace.close()
            // An HTTP read dispatched before close can still reach the interceptor during cancellation.
            runBlocking { withTimeout(5000) { scope.coroutineContext.job.children.toList().joinAll() } }
            val count = requests.size
            workspace.observe("other", true)
            runBlocking { withTimeout(5000) { scope.coroutineContext.job.children.toList().joinAll() } }
            assertEquals(count, requests.size)
        } finally { workspace.close(); client.close(); scope.cancel() }
    }
    @Test fun unsupportedProfileUsesOnlyItsCacheAndNeverProbesAnotherAccount() {
        val requests=AtomicInteger()
        val client=HermesClient("https://fixture.invalid",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder()
            .addInterceptor { requests.incrementAndGet(); error("Unsupported profile must not probe") }.build())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val first=Cache().apply { writeCoding(snapshot("root")) }
            val offline=CodingActivityWorkspace(scope,client,first,false)
            offline.observe("root",true); assertNotNull(offline.snapshot)
            val separate=CodingActivityWorkspace(scope,client,Cache(),false)
            separate.observe("root",true); assertNull(separate.snapshot)
            assertEquals(0,requests.get()); offline.close(); separate.close()
        } finally { client.close(); scope.cancel() }
    }

    @Test fun stoppingDesktopWorkVerifiesTheNativeParentAndOriginalChat() {
        val workId = "a".repeat(32)
        val requests = CopyOnWriteArrayList<String>()
        var status = RunStatus("run_one", "running", sessionId = "other")
        val client = HermesClient("https://fixture.invalid/p/work", { "fixture" },
            httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                requests += "${request.method} $path"
                val body = when (path) {
                    "/p/work/api/bellpull/sessions/root/coding" -> wire.encodeToString(snapshot("root"))
                    "/p/work/v1/runs/run_one" -> wire.encodeToString(status)
                    "/p/work/v1/runs/run_one/stop" -> wire.encodeToString(RunStatus("run_one", "stopping"))
                    else -> error("Unexpected call $path")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = Cache().apply { writeCoding(snapshot("root")) }
        val workspace = CodingActivityWorkspace(scope, client, cache, true, pollMs = 60_000)
        try {
            workspace.observe("root", false)
            workspace.stopObserved(workId)
            assertTrue(requests.isEmpty())
            workspace.observe("root", true)
            waitUntil { !workspace.disconnected && !workspace.loading }
            workspace.stopObserved(workId)
            waitUntil { !workspace.stopInFlight }
            assertTrue(workspace.stopNotice!!.contains("confirm"))
            status = status.copy(sessionId = "root", status = "completed")
            workspace.stopObserved(workId)
            waitUntil { !workspace.stopInFlight }
            assertTrue(workspace.stopNotice!!.contains("completed"))
            assertFalse(requests.any { it.startsWith("POST") })
            status = status.copy(status = "running")
            workspace.stopObserved(workId)
            waitUntil { !workspace.stopInFlight }
            assertEquals("Stop requested.", workspace.stopNotice)
            assertEquals(listOf("POST /p/work/v1/runs/run_one/stop"), requests.filter { it.startsWith("POST") })
            assertEquals("running", workspace.snapshot!!.works.single().state)
        } finally { workspace.close(); client.close(); scope.cancel() }
    }
}
