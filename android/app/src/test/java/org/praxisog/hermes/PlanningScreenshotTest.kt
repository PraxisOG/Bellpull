package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*
import kotlinx.coroutines.*
import org.praxisog.hermes.core.HermesClient
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@Config(sdk=[35],qualifiers="w393dp-h850dp")
class PlanningScreenshotTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var previousTimeZone: TimeZone
    @Before fun fixedSampleTimeZone() {
        previousTimeZone=TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }
    @After fun restoreTimeZone() { TimeZone.setDefault(previousTimeZone) }
    private fun screen(week: Boolean=false) {
        val day=LocalDate.of(2026,10,12); val zone=ZoneId.systemDefault(); val zero=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val utc=day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val events=listOf(PhoneCalendarEvent(1,1,"Costume build",zero+7*3600000,zero+9*3600000,false,zone.id,false,"a","key"),
            PhoneCalendarEvent(2,1,"Team call",zero+8*3600000,zero+9*3600000,false,zone.id,false,"b","key"),
            PhoneCalendarEvent(3,1,"Workshop day",utc,utc+86400000,true,"UTC",false,"c","key"))
        var opened=0
        compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Calendar",style=MaterialTheme.typography.titleLarge)
                CalendarBlocksView(day,events,listOf(Reminder("a".repeat(32),"Bring 12.5 mm bolts",zero+9*3600000,"pending",1)),{}, { opened++ },{})
            }
        } } }
        if (week) compose.onNodeWithText("Week").performClick()
        compose.onNodeWithText("All day · Workshop day").assertIsDisplayed()
        compose.onNodeWithText("Costume build").performClick(); assertEquals(1,opened)
        captureScreenRoboImage()
    }
    @Test fun calendarDayCover()=screen()
    @Test @Config(qualifiers="w840dp-h900dp-night") fun calendarWeekUnfolded()=screen(true)
    private fun planningScreen() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR,android.Manifest.permission.WRITE_CALENDAR)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=HermesClient("https://fixture.invalid",{"fixture"})
        val cache=PlanningCacheFixture().apply { planning=PlanningSnapshot(1,listOf(
            Reminder("a".repeat(32),"Finish costume measurements",1791630000000,"pending",1),
            Reminder("b".repeat(32),"Reference photo saved",1791630000000,"completed",2)),routinesAvailable=true,observedAt=1.0) }
        val workspace=PlanningWorkspace(scope,client,cache,app,true)
        try {
            compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) { PlanningScreen(workspace,false,{}) } } }
            compose.onNodeWithText("Finish costume measurements").assertIsDisplayed()
            compose.onNodeWithText("Complete").assertExists()
            captureScreenRoboImage()
            compose.mainClock.autoAdvance=false
            compose.onNodeWithText("Add reminder").performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("New Reminder").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.mainClock.autoAdvance=true
            compose.onNodeWithText("Routines").performClick()
            compose.onNodeWithText("Add Hermes routine").assertIsEnabled()
        } finally { workspace.close(); client.close(); scope.cancel() }
    }
    @Test fun remindersCover()=planningScreen()
    @Test @Config(qualifiers="w840dp-h900dp-night") fun remindersUnfolded()=planningScreen()
}
