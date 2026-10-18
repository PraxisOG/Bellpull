package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.time.*

/** Real touch and encrypted persistence in the disposable fixture app only. */
@RunWith(AndroidJUnit4::class)
class TodayDeviceTest {
    private val ui = DeviceFixtureUi
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun revealAndTap(label: String) {
        repeat(18) {
            if (ui.text(label) != null) { ui.tapText(label); return }
            ui.scroll(true)
        }
        fail("Missing dashboard action: $label")
    }
    @Test fun offlineCompleteAndSnoozeSurviveEncryptedCacheReopen() = ui.withWindows {
        // No planning support means the fixture cannot contact any server.
        val client = HermesClient("http://127.0.0.1:9", { "fixture-only" })
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val profile = "today-device-fixture"
        val cache = SessionCache(context,profile)
        cache.clear()
        val now = System.currentTimeMillis()
        val complete = Reminder("a".repeat(32),"Device complete reminder",now-120_000,"pending",1)
        val snooze = Reminder("b".repeat(32),"Device snooze reminder",now-60_000,"pending",1)
        cache.updateDelivery { it.reconcile(PlanningSnapshot(1,listOf(complete,snooze),observedAt=now/1000.0),now) }
        lateinit var delivery: DeliveryWorkspace
        lateinit var planning: PlanningWorkspace
        ui.main {
            delivery = DeliveryWorkspace(context,profile,scope,cache,client,false)
            planning = PlanningWorkspace(scope,client,cache,context,false,delivery)
        }
        try {
            ui.awaitMain("Delivery ready") { delivery.ready }
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                fun render() = scene.onActivity { activity -> activity.setContent { HermesTheme {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) { PlanningScreen(planning,false,requestPermission={}) }
                } } }
                render()
                ui.await("Dashboard reminder") { ui.text(complete.title)!=null }
                revealAndTap("Complete")
                ui.awaitMain("Complete persisted") { delivery.state.operations.size==1 && delivery.state.reminders().first().state=="completed" }
                revealAndTap("Snooze 1h")
                ui.awaitMain("Snooze persisted") { delivery.state.operations.size==2 }
                val operations=cache.readDelivery().operations
                assertEquals(setOf("complete","snooze"),operations.map { it.action }.toSet())
                assertTrue(operations.single { it.action=="snooze" }.dueMs!! >= now+3_600_000)
                scene.recreate(); render()
                val reopened=SessionCache(context,profile)
                try {
                    assertEquals(operations,reopened.readDelivery().operations)
                    assertEquals("completed",reopened.readDelivery().reminders().first().state)
                    assertTrue(reopened.readDelivery().reminders().last().dueMs > now)
                } finally { reopened.close() }
                ui.screenshot("today-offline-actions")
                ui.instrumentation.sendStatus(0, Bundle().apply { putString("today_offline_actions","complete and snooze persisted; encrypted cache reopened; no server calls") })
            }
        } finally {
            ui.main { planning.close(); delivery.close() }; scope.cancel(); client.close(); cache.clear(); cache.close()
        }
    }
    @Test fun eventProposalAndChatLinksKeepTheirSources() = ui.withWindows {
        val now=System.currentTimeMillis()
        val event=PhoneCalendarEvent(22,1,"Device calendar event",now,now+3_600_000,false,"UTC",false,"fixture","fixture")
        val proposal=CalendarProposal("c".repeat(32),"fixture","create_event","pending","Device proposal",now,now+3_600_000)
        val data=TodayData(LocalDate.now(),listOf(event),emptyList(),listOf(proposal),
            listOf(TodayWork("approval-source","Device decision","Needs approval")),
            listOf(TodayWork("running-source","Device running job","Running")),
            listOf(TodayWork("reply-source","Device reply","Unread reply","Saved reply body")),emptySet())
        val opened=mutableListOf<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val source=TodaySource("Saved fixture records")
                    TodayDashboard(data,now,source,source,source,{_,_->},
                        { opened.add("event:${it.id}") },{ opened.add("proposal:${it.id}") },{ opened.add("chat:$it") },{},{},{})
                }
            } } }
            ui.await("Event loaded") { ui.text(event.title)!=null }
            ui.screenshot("today-overview")
            revealAndTap("Open event")
            revealAndTap("Review proposal")
            revealAndTap("Review in chat")
            revealAndTap("Open chat")
            repeat(12) {
                if (ui.find { it.contentDescription?.toString()=="Open chat: Device reply" }==null) ui.scroll(true)
            }
            ui.tap(checkNotNull(ui.find { it.contentDescription?.toString()=="Open chat: Device reply" }))
            ui.awaitMain("Source actions") { opened.size==5 }
            assertEquals(listOf("event:22","proposal:${proposal.id}","chat:approval-source","chat:running-source","chat:reply-source"),opened)
            assertEquals(1,data.decisions.size); assertEquals(1,data.unread.size)
            ui.screenshot("today-work")
        }
    }
}
