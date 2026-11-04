package org.praxisog.hermes

import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.Session
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionRefreshStoreTest {
    private class Cache : SessionSnapshots {
        var saved: SessionSnapshots.Snapshot? = null
        override fun read() = saved
        override fun write(sessions: List<Session>, atEpochSeconds: Double) { saved = SessionSnapshots.Snapshot(sessions, atEpochSeconds) }
        override fun clear() { saved = null }
    }
    private fun await(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(what, condition())
    }
    @Test fun refreshKeepsCachedRowsCoalescesPullsAndClearsBusyOnFailureAndRecovery() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = Cache()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val number = requests.incrementAndGet()
            if (number == 2) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (number == 2) 503 else 200).message("fixture")
                .body((if (number == 2) """{"error":"offline"}""" else """{"data":[{"id":"chat-$number","source":"cli"}]}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("http://fixture.invalid", { "fixture" }, httpClient = http)
        val store = HermesStore(scope, client, cache = cache, sessionSource = "cli")
        try {
            store.loadSessions()
            await("initial list") { store.sessions is UiState.Ready && !store.refreshingSessions }
            store.refreshSessions()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(store.refreshingSessions)
            assertEquals("chat-1", (store.sessions as UiState.Ready).value.single().id)
            repeat(3) { store.refreshSessions() }
            assertEquals(2, requests.get())
            release.countDown()
            await("failed refresh ends") { !store.refreshingSessions }
            assertNotNull(store.offline)
            assertEquals("chat-1", (store.sessions as UiState.Ready).value.single().id)
            store.refreshSessions()
            await("recovery ends") { !store.refreshingSessions }
            assertEquals("chat-3", (store.sessions as UiState.Ready).value.single().id)
            assertNull(store.offline)
            assertEquals("cli", store.sessionSource)
        } finally { release.countDown(); store.close(); scope.cancel(); client.close() }
    }
    @Test fun anOlderResponseCannotClearTheIndicatorOrOverwriteANewerSourceRequest() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = Array(2) { CountDownLatch(1) }
        val releases = Array(2) { CountDownLatch(1) }
        val done = CountDownLatch(1)
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val source = chain.request().url.queryParameter("source")
            val index = if (source == "cli") 1 else 0
            started[index].countDown()
            check(releases[index].await(5, TimeUnit.SECONDS))
            if (index == 0) done.countDown()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"data":[{"id":"$source","source":"$source"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("http://fixture.invalid", { "fixture" }, httpClient = http)
        val store = HermesStore(scope, client, sessionSource = "api_server")
        try {
            store.loadSessions()
            assertTrue(started[0].await(5, TimeUnit.SECONDS))
            store.chooseSessionSource("cli")
            assertTrue(started[1].await(5, TimeUnit.SECONDS))
            releases[0].countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            // Drain the older result while the selected source is still held at the server.
            Thread.sleep(100)
            assertTrue(store.refreshingSessions)
            assertTrue(store.sessions is UiState.Loading)
            releases[1].countDown()
            await("new source") { !store.refreshingSessions }
            assertEquals("cli", (store.sessions as UiState.Ready).value.single().id)
        } finally { releases.forEach { it.countDown() }; store.close(); scope.cancel(); client.close() }
    }
}
