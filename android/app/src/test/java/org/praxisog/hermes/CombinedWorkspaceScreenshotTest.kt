package org.praxisog.hermes

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.TimeZone

/** Same live run and client through both destinations; navigation cannot stop/readmit it. */
@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@Config(sdk=[35])
class CombinedWorkspaceScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousTimeZone: TimeZone
    @Before fun fixedSampleTimeZone() {
        previousTimeZone=TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }
    @After fun restoreTimeZone() { TimeZone.setDefault(previousTimeZone) }
    private fun navigate(cover: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val admissions = AtomicInteger()
        val requests = CopyOnWriteArrayList<Request>()
        val work = CodingWork("a".repeat(32), "root", "run_combined", "proc_fixture", state="running")
        val codingSnapshot = CodingSnapshot(1,"root",listOf("root"),1,true,false,works=listOf(work),
            rows=listOf(CodingRow(1,work.id,"response",123,"text","OpenCode response","Coding continues while Today is open.")),observedAt=1.0)
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request().also(requests::add)
            val path = request.url.encodedPath
            var status = 200
            val body = when {
                path.endsWith("/v1/runs") && request.method == "POST" -> {
                    admissions.incrementAndGet(); status=202
                    """{"run_id":"run_combined","status":"running"}"""
                }
                path.endsWith("/events") -> "id: 1\nevent: message.delta\ndata: {\"delta\":\"Live answer\"}\n\n"
                path.endsWith("/v1/runs/run_combined") -> """{"run_id":"run_combined","session_id":"root","status":"running"}"""
                path.endsWith("/coding") -> Json.encodeToString(codingSnapshot)
                path.endsWith("/planning") -> """{"version":1,"reminders":[{"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","title":"Review coding output","due_ms":1609459200000,"state":"pending","revision":1}],"observed_at":1}"""
                else -> error("Unexpected combined navigation request: ${request.method} $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody((if (path.endsWith("/events")) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        val client = HermesClient("https://fixture.invalid/p/work", { "fixture" }, httpClient=http)
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val drafts = ChatDrafts()
        val journal = object: RunJournal {
            val records = mutableMapOf<String,RunSubmission>()
            override fun readRuns() = records.values.toList()
            override fun writeRun(value: RunSubmission): Boolean { records[value.sessionId]=value; return true }
        }
        val runs = DurableRuns(scope, client, journal, drafts, 86400)
        val accepted = java.util.concurrent.atomic.AtomicBoolean()
        runs.onAccepted = { accepted.set(true) }
        val coding = CodingActivityWorkspace(scope,client,supported=true,pollMs=50)
        val planning = PlanningWorkspace(scope,client,PlanningCacheFixture(),app,true)
        val session = Session("root",source="api_server",title="Combined workspace check")
        try {
            compose.runOnUiThread { runs.send("root","Continue the coding task",emptySet()) }
            // A run ID is published before the submitted draft is acknowledged.
            // Wait for that transaction to finish before typing the next draft.
            compose.waitUntil(5000) { accepted.get() }
            compose.runOnUiThread { drafts.set("root",TextFieldValue("Preserve this unsent follow-up")) }
            compose.setContent {
                val state = runs.states["root"]!!
                HermesApp(UiState.Ready(listOf(session)), UiState.Ready(emptyList()), state.turn,
                    session.id,session,1791774000.0, {}, {}, { false }, { runs.stop("root") }, {}, {}, {},
                    draft=drafts["root"],onDraftChange={ drafts.set("root",it) },
                    preferences=BellpullPreferences(reduceMotion=true), runPresentation=state,
                    runLabels=mapOf("root" to "Running"),coding=coding,
                    planningView={ PlanningScreen(planning,true,clock={1791774000000L},requestPermission={}) })
            }
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("OpenCode activity").performClick()
            compose.onNodeWithTag("coding-activity").assertExists()
            try { compose.waitUntil(5000) { coding.snapshot?.works?.singleOrNull()?.id == work.id } }
            catch (error: Exception) { throw AssertionError("Coding=${coding.problem}; loading=${coding.loading}; requests=${requests.map { it.url.encodedPath }}", error) }
            compose.onNodeWithText("OpenCode · Running").assertExists()
            if (cover) compose.onNodeWithContentDescription("Back to sessions").performClick()
            compose.onNodeWithText("Today").performClick()
            compose.onNodeWithTag("coding-activity").assertDoesNotExist()
            try { compose.waitUntil(5000) { planning.snapshot?.reminders?.isNotEmpty() == true } }
            catch (error: Exception) {
                throw AssertionError("Planning=${planning.problem}; loading=${planning.loading}; requests=${requests.map { it.url.encodedPath }}", error)
            }
            compose.onNodeWithText("Review coding output").assertExists()
            captureScreenRoboImage()
            if (cover) compose.onNodeWithContentDescription("Back to sessions").performClick()
            compose.onNodeWithText("Chats").performClick()
            compose.onNodeWithText("OpenCode · Running").assertExists()
            compose.onNodeWithText("Chat / follow up").performClick()
            compose.onNodeWithText("Preserve this unsent follow-up").assertExists()
            assertTrue(runs.states["root"]!!.busy)
            assertEquals("run_combined",runs.states["root"]!!.submission.runId)
            assertEquals(1,admissions.get())
            assertFalse(requests.any { it.url.encodedPath.endsWith("/stop") })
            assertTrue(requests.all { it.url.encodedPath.startsWith("/p/work/") })
        } finally { coding.close(); planning.close(); runs.close(); scope.cancel(); client.close() }
    }
    @Test @Config(qualifiers="w393dp-h850dp") fun activityTodayAndDraftCover() = navigate(true)
    @Test @Config(qualifiers="w840dp-h900dp-night") fun activityTodayAndDraftUnfolded() = navigate(false)
}
