package org.praxisog.hermes

import android.Manifest
import android.content.pm.ProviderInfo
import android.provider.CalendarContract
import kotlinx.coroutines.*
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

/** Audit probes assert the observed defect, not the desired fixed behavior. */
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
        val client=HermesClient("https://fixture.invalid",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request=chain.request()
            if (request.method=="PUT") {
                val buffer=Buffer(); request.body!!.writeTo(buffer)
                if (buffer.readUtf8().contains("\"revoke\":true")) commits.add("revoke")
                else { uploadStarted.countDown(); check(releaseUpload.await(8,TimeUnit.SECONDS)); commits.add("upload") }
            }
            val response=if (request.method=="GET") """{"version":1,"reminders":[],"observed_at":1}""" else "{}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspace=PlanningWorkspace(scope,client,cache,app,true)
        try {
            workspace.refresh(); assertTrue(uploadStarted.await(8,TimeUnit.SECONDS))
            workspace.savePolicy(cache.policy.copy(sharing=false))
            waitUntil { commits.contains("revoke") && !workspace.revokePending }
            assertFalse(cache.policy.sharing)
            releaseUpload.countDown(); waitUntil { commits.contains("upload") }
            assertEquals(listOf("revoke","upload"),commits.toList())
            println("AUDIT: sharing=false, revokePending=false, server commit order=$commits")
        } finally { releaseUpload.countDown(); workspace.close(); client.close(); scope.cancel() }
    }
    @Test fun publicHostnamePassesPrivateHttpValidation() {
        val ordinary="http://example.invalid/v1/audio/transcriptions"
        val disguised="http://100.64.0.1.example.invalid/v1/audio/transcriptions"
        assertThrows(IllegalArgumentException::class.java) { PrivateTranscription.validateEndpoint(ordinary) }
        assertEquals(disguised,PrivateTranscription.validateEndpoint(disguised))
        println("AUDIT: ordinary public HTTP rejected; public hostname with numeric labels accepted")
    }
    @Test fun whitespaceTitleCommitsThenReportsFailure() {
        val source=phone.sources().first()
        val policy=CalendarPolicy(setOf(source.key),writes=true,destination=source.key)
        val draft=CalendarDraft("Before",2000000000000,2000003600000)
        val old=phone.create("a".repeat(32),policy,source,draft)
        val failure=assertThrows(IllegalStateException::class.java) { phone.change(policy,source,old,draft.copy(title=" After ")) }
        assertEquals("After",phone.event(old.id,source)!!.title)
        println("AUDIT: title committed as After, exception=${failure.message}")
    }
}
