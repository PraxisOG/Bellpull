package org.praxisog.hermes

import android.Manifest
import android.content.pm.ProviderInfo
import android.provider.CalendarContract
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.core.PrivateTranscription
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Audit probes assert observed defects, except repaired findings such as F03. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class AuditPlanningProbeTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private lateinit var provider: CalendarProviderFixture
    private lateinit var phone: PhoneCalendar
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        provider=CalendarProviderFixture()
        provider.attachInfo(app,ProviderInfo().apply { authority=CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        phone=PhoneCalendar(app)
    }
    private fun waitUntil(condition: () -> Boolean) {
        val end=System.nanoTime()+8_000_000_000
        while (!condition()) { check(System.nanoTime()<end) { "Timed out" }; Thread.sleep(10) }
    }
    @Test fun canceledUploadCanCommitAfterConfirmedRevocation() {
        val source=phone.sources().first()
        val cache=PlanningCacheFixture().apply { policy=CalendarPolicy(setOf(source.key),sharing=true,writes=true,destination=source.key) }
        val now=System.currentTimeMillis()
        phone.create("a".repeat(32),cache.policy,source,CalendarDraft("Audit fixture",now+60000,now+120000))
        val uploadStarted=CountDownLatch(1); val releaseUpload=CountDownLatch(1)
        val commits=CopyOnWriteArrayList<String>()
        var serverGeneration=0L
        var serverSnapshot=false
        val client=HermesClient("https://fixture.invalid",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request=chain.request()
            var code=200
            var response=if (request.method=="GET") """{"version":1,"reminders":[],"observed_at":1}""" else "{}"
            if (request.method=="PUT") {
                val buffer=Buffer(); request.body!!.writeTo(buffer)
                val body=Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                if (body["revoke"]?.jsonPrimitive?.booleanOrNull==true) synchronized(commits) {
                    serverGeneration++; serverSnapshot=false
                    response="""{"revoked":true,"generation":$serverGeneration}"""
                    commits.add("revoke")
                } else {
                    uploadStarted.countDown(); check(releaseUpload.await(8,TimeUnit.SECONDS))
                    // Simulate a delivered request even when OkHttp was canceled:
                    // only the durable server generation prevents its late commit.
                    synchronized(commits) {
                        if ((body["generation"]?.jsonPrimitive?.longOrNull ?: 0L)!=serverGeneration) {
                            code=409; response="""{"error":{"message":"Stale calendar policy generation."}}"""
                            commits.add("upload-rejected-409")
                        } else {
                            serverSnapshot=true
                            response="""{"generation":$serverGeneration}"""
                            commits.add("upload")
                        }
                    }
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspace=PlanningWorkspace(scope,client,cache,app,true)
        try {
            workspace.refresh(); assertTrue(uploadStarted.await(8,TimeUnit.SECONDS))
            workspace.savePolicy(cache.policy.copy(sharing=false))
            waitUntil { commits.contains("revoke") && !workspace.revokePending }
            assertFalse(cache.policy.sharing)
            assertEquals(1L,cache.policy.generation)
            releaseUpload.countDown(); waitUntil { commits.contains("upload-rejected-409") }
            assertEquals(listOf("revoke","upload-rejected-409"),commits.toList())
            synchronized(commits) { assertFalse(serverSnapshot) }
            assertNull(workspace.calendarProblem)
            println("AUDIT: sharing=false, revokePending=false, stale upload rejected 409, snapshot absent; order=$commits")
        } finally { releaseUpload.countDown(); workspace.close(); client.close(); scope.cancel() }
    }
    @Test fun publicHostnameRejectedByPrivateHttpValidation() {
        val ordinary="http://example.invalid/v1/audio/transcriptions"
        val disguised="http://100.64.0.1.example.invalid/v1/audio/transcriptions"
        assertThrows(IllegalArgumentException::class.java) { PrivateTranscription.validateEndpoint(ordinary) }
        assertThrows(IllegalArgumentException::class.java) { PrivateTranscription.validateEndpoint(disguised) }
        for (url in listOf("http://100.64.0.1/v1/audio/transcriptions", "http://127.0.0.1:18647/transcriptions"))
            assertEquals(url,PrivateTranscription.validateEndpoint(url))
        println("AUDIT: public HTTP hostnames rejected; valid Tailscale and loopback HTTP accepted")
    }
    @Test fun whitespaceTitleCommitsThenReportsFailure() {
        val source=phone.sources().first()
        val policy=CalendarPolicy(setOf(source.key),writes=true,destination=source.key)
        val draft=CalendarDraft("Before",2000000000000,2000003600000)
        val old=phone.create("a".repeat(32),policy,source,draft)
        val receipt=phone.change(policy,source,old,draft.copy(title=" After "))
        assertTrue(receipt.contains("Updated phone calendar"))
        assertEquals("After",phone.event(old.id,source)!!.title)
        println("AUDIT: title committed as After, receipt=$receipt")
    }
}
