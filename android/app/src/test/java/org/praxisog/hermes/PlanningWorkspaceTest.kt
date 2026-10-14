package org.praxisog.hermes

import android.Manifest
import kotlinx.coroutines.*
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class PlanningWorkspaceTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun permissions() { shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR) }
    private fun waitUntil(condition: () -> Boolean) {
        val end=System.nanoTime()+5_000_000_000
        while (!condition()) { check(System.nanoTime()<end) { "Timed out" }; Thread.sleep(10) }
    }
    @Test fun offlineRevocationSurvivesRestartAndNeverAdmitsInference() {
        val requests=CopyOnWriteArrayList<Request>()
        var fail=true
        val client=HermesClient("https://fixture.invalid/p/work",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request=chain.request(); requests.add(request)
            if (request.method=="PUT" && fail) throw IOException("Offline")
            val response=if (request.method=="GET") """{"version":1,"reminders":[],"observed_at":1}""" else """{"revoked":true,"generation":1}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val cache=PlanningCacheFixture().apply { policy=CalendarPolicy(sharing=true) }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var first: PlanningWorkspace?=null; var second: PlanningWorkspace?=null
        try {
            first=PlanningWorkspace(scope,client,cache,app,true); first.refresh()
            waitUntil { cache.revoke && requests.any { it.method=="PUT" } && !first.loading }
            assertTrue(first.revokePending)
            first.close(); fail=false
            second=PlanningWorkspace(scope,client,cache,app,true); second.refresh()
            waitUntil { !cache.revoke && requests.count { it.method=="PUT" }>=2 }
            assertEquals(1L,cache.policy.generation)
            assertTrue(requests.all { it.url.encodedPath.startsWith("/p/work/api/bellpull/") })
            requests.filter { it.method=="PUT" }.forEach { request ->
                val buffer=Buffer(); request.body!!.writeTo(buffer); assertEquals("{\"revoke\":true,\"generation\":0}",buffer.readUtf8())
            }
        } finally { first?.close(); second?.close(); client.close(); scope.cancel() }
    }
    @Test fun verifiedProposalReceiptRetriesReportingWithoutRepeatingPhoneWrite() {
        val requests=CopyOnWriteArrayList<Request>()
        val client=HermesClient("https://fixture.invalid",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request=chain.request().also(requests::add)
            val body=if (request.method=="GET") """{"version":1,"reminders":[],"observed_at":1}""" else "{}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val cache=PlanningCacheFixture(); val id="a".repeat(32); cache.receipts[id]="Verified previous deletion."
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspace=PlanningWorkspace(scope,client,cache,app,true)
        try {
            workspace.applyProposal(CalendarProposal(id,cache.calendarDevice(),"delete_event","pending","Old event",eventId="12",fingerprint="old"))
            waitUntil { requests.any { it.method=="POST" } && !workspace.busy }
            assertEquals("Verified previous deletion.",workspace.result)
            assertFalse(workspace.canWrite()) // No provider write was possible or needed.
        } finally { workspace.close(); client.close(); scope.cancel() }
    }
}

internal class PlanningCacheFixture: PlanningCache {
    var planning: PlanningSnapshot?=null; var calendar: PhoneCalendarSnapshot?=null
    var policy=CalendarPolicy(); var revoke=false; val receipts=mutableMapOf<String,String>()
    override fun readPlanning()=planning
    override fun writePlanning(value: PlanningSnapshot) { planning=value }
    override fun readCalendar()=calendar
    override fun writeCalendar(value: PhoneCalendarSnapshot?) { calendar=value }
    override fun readCalendarPolicy()=policy
    override fun writeCalendarPolicy(value: CalendarPolicy) { policy=value }
    override fun calendarDevice()="d".repeat(32)
    override fun calendarRevokePending()=revoke
    override fun calendarRevokePending(value: Boolean) { revoke=value }
    override fun calendarReceipt(id: String)=receipts[id]
    override fun calendarReceipt(id: String,result: String) { receipts[id]=result }
}
