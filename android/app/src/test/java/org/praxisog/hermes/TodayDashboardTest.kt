package org.praxisog.hermes

import android.Manifest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.time.*
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="w393dp-h850dp")
class TodayDashboardTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousZone: TimeZone
    private val now = Instant.parse("2026-10-18T17:00:00Z").toEpochMilli()
    private val reminder = Reminder("a".repeat(32),"Pack the workshop kit",now-3_600_000,"pending",1)
    private val event = PhoneCalendarEvent(1,1,"Design review",now+3_600_000,now+7_200_000,false,"America/Los_Angeles",false,"fingerprint","chosen")
    private fun data() = TodayData(LocalDate.of(2026,10,18),listOf(event),listOf(reminder),emptyList(),
        listOf(TodayWork("decision","Review the build","Needs approval")),
        listOf(TodayWork("running","Prepare release notes","Running")),
        listOf(TodayWork("reply","Workshop checklist","Unread reply","Your checklist is ready.")),emptySet())
    @Before fun zone() { previousZone=TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")) }
    @After fun restoreZone() { TimeZone.setDefault(previousZone) }
    private val current = TodaySource("Updated 10 minutes ago")
    private fun screen(value: TodayData = data(), source: TodaySource = current) {
        compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
            TodayDashboard(value,now,source,source,source,{_,_->},{},{},{},{},{},{})
        } } }
    }
    @Test fun overviewCover() { screen(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers="w840dp-h900dp-night") fun overviewUnfolded() { screen(); compose.onRoot().captureRoboImage() }
    @Test fun savedOverviewCover() { screen(source=TodaySource("Saved on this phone · Hermes is unavailable. Refresh to check current status.",saved=true)); compose.onRoot().captureRoboImage() }
    @Test fun unavailableDoesNotPretendTheDayIsEmpty() {
        screen(data().copy(events=emptyList(),reminders=emptyList(),decisions=emptyList(),running=emptyList(),unread=emptyList()),
            TodaySource("Not available. Connect or enable access to refresh.",available=false))
        compose.onNodeWithText("No events today.").assertDoesNotExist()
        compose.onNodeWithText("No due or overdue reminders.").assertDoesNotExist()
        compose.onNodeWithText("No pending decisions in the available records.").assertDoesNotExist()
        compose.onRoot().captureRoboImage()
    }
    @Test fun currentEmptyDayHasExplicitEmptyStates() {
        screen(data().copy(events=emptyList(),reminders=emptyList(),decisions=emptyList(),running=emptyList(),unread=emptyList()))
        compose.onNodeWithText("No events today.").assertExists()
        compose.onNodeWithText("No due or overdue reminders.").assertExists()
        compose.onNodeWithTag("today-dashboard").performScrollToNode(hasText("No unread replies in this phone’s records."))
        compose.onNodeWithText("No unread replies in this phone’s records.").assertIsDisplayed()
    }
    @Test fun actionsKeepTheirSourcesAndPendingReminderCannotBeRepeated() {
        val actions=mutableListOf<String>()
        var value by mutableStateOf(data())
        compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
            TodayDashboard(value,now,current,current,current,
                { item,action -> actions.add("${item.id}:$action"); value=value.copy(pendingReminderIds=setOf(item.id)) },
                { actions.add("event:${it.id}") },{ actions.add("proposal:${it.id}") },{ actions.add("chat:$it") },{},{},{})
        } } }
        compose.onNodeWithTag("today-event-${event.id}-${event.start}").performClick()
        compose.onNodeWithTag("today-complete-${reminder.id}").performScrollTo().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("today-snooze-${reminder.id}").assertIsNotEnabled()
        compose.onNodeWithTag("today-open-decision-Needs approval").performScrollTo().performClick()
        compose.onNodeWithTag("today-open-reply-Unread reply").performScrollTo().performClick()
        assertEquals(listOf("event:1","${reminder.id}:complete","chat:decision","chat:reply"),actions)
        assertEquals(1,value.unread.size)
        compose.runOnIdle { value=value.copy(pendingReminderIds=emptySet()) }
        compose.onNodeWithTag("today-snooze-${reminder.id}").performScrollTo().performClick()
        assertEquals("${reminder.id}:snooze",actions.last())
    }
    @Test @Config(qualifiers="w840dp-h900dp") fun openingTheAlreadySelectedChatLeavesTodayAndPreservesDraft() {
        var focus by mutableIntStateOf(0)
        var opened: String? = null
        var sends=0
        val chat=Session("reply",title="Workshop checklist")
        compose.setContent { HermesApp(UiState.Ready(listOf(chat)),UiState.Ready(listOf(Message(1,role="assistant",content="Saved answer"))),
            TurnState.Idle,chat.id,chat,now/1000.0,{}, {},{ sends++; true },{},{},{},{},
            focusChatRevision=focus,draft=TextFieldValue("Keep my draft"),preferences=BellpullPreferences(reduceMotion=true),
            planningView={ TodayDashboard(data(),now,current,current,current,{_,_->},{},{},{ opened=it; focus++ },{},{},{}) }) }
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithTag("today-open-reply-Unread reply").performScrollTo().performClick()
        compose.onNodeWithTag("today-dashboard").assertDoesNotExist()
        compose.onNodeWithText("Saved answer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message Hermes").assertTextContains("Keep my draft")
        assertEquals("reply",opened); assertEquals(0,sends)
    }
    @Test fun refreshingTodayOnlyUsesExistingReadApis() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val requests=CopyOnWriteArrayList<Request>()
        val client=HermesClient("https://fixture.invalid/p/today",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request=chain.request().also(requests::add)
            val response=when {
                request.method!="GET" -> error("Today admitted a mutation: ${request.url.encodedPath}")
                request.url.encodedPath.endsWith("/planning") -> """{"version":1,"reminders":[],"observed_at":1792342800}"""
                request.url.encodedPath.endsWith("/sessions") -> """{"data":[],"has_more":false}"""
                else -> error("Unexpected Today request: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspace=PlanningWorkspace(scope,client,PlanningCacheFixture(),app,true)
        val store=HermesStore(scope,client)
        try {
            compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
                PlanningScreen(workspace,true,store=store,clock={now},requestPermission={})
            } } }
            compose.waitUntil(5000) { workspace.snapshot!=null && store.sessions is UiState.Ready }
            compose.onNodeWithTag("today-dashboard").assertExists()
            assertTrue(requests.any { it.url.encodedPath.endsWith("/planning") })
            assertTrue(requests.any { it.url.encodedPath.endsWith("/sessions") })
            assertTrue(requests.all { it.method=="GET" && it.url.encodedPath.startsWith("/p/today/") })
        } finally { workspace.close(); store.close(); scope.cancel(); client.close() }
    }

    @Test fun inboxNotificationDoesNotOverrideLaterTabChoiceAndHandlesActivityRevisionReset() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val cache=SessionCache(app,"today-notifications",app.getSharedPreferences("today-notifications",0))
        val client=HermesClient("http://127.0.0.1:9",{"unused"})
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val delivery=DeliveryWorkspace(app,"today-notifications",scope,cache,client,false)
        val workspace=PlanningWorkspace(scope,client,cache,app,false,delivery)
        var revision by mutableIntStateOf(7)
        var visible by mutableStateOf(true)
        try {
            compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
                val saved=rememberSaveableStateHolder()
                if (visible) saved.SaveableStateProvider("planning") {
                    PlanningScreen(workspace,false,inboxRevision=revision,clock={now},requestPermission={})
                }
            } } }
            compose.onNodeWithTag("planning-tab-Inbox").assertIsSelected()
            compose.onNodeWithTag("planning-tab-Reminders").performClick()
            compose.runOnIdle { visible=false }; compose.waitForIdle()
            compose.runOnIdle { visible=true }
            compose.onNodeWithTag("planning-tab-Reminders").assertIsSelected()
            // MainActivity's event counter starts again after recreation.
            compose.runOnIdle { revision=0 }; compose.waitForIdle()
            compose.runOnIdle { revision=1 }
            compose.onNodeWithTag("planning-tab-Inbox").assertIsSelected()
        } finally { workspace.close(); delivery.close(); scope.cancel(); client.close(); cache.close() }
    }
}
