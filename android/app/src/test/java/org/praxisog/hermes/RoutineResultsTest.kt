package org.praxisog.hermes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w393dp-h850dp")
class RoutineResultsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readSavedAnswerFromRoutinesWithoutStartingAnotherJob() {
        val calls = CopyOnWriteArrayList<Request>()
        val revision = "b".repeat(64)
        val ref = """"job":"job & 1","stamp":"2026-11-24_17-45-00","revision":"$revision""""
        val answer = "Stored answer."
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            val response = when (request.url.encodedPath) {
                "/p/work/api/bellpull/planning/results" -> """{"results":[{$ref,"size":${answer.length}}]}"""
                "/p/work/api/bellpull/planning/result" -> """{$ref,"offset":0,"text":"$answer"}"""
                else -> error("Unexpected request from result reader")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = HermesClient("https://fixture.invalid/p/work", { "fixture" }, httpClient=http)
        val routine = Routine("job & 1", name="Morning report", fingerprint="fixture",
            latestExecution=RoutineExecution("e1", "completed", "queued"))
        val cache = PlanningCacheFixture().apply {
            planning = PlanningSnapshot(1, emptyList(), routines=listOf(routine), routinesAvailable=true,
                observedAt=1.0, savedResultsAvailable=true)
        }
        val workspace = PlanningWorkspace(scope, client, cache, RuntimeEnvironment.getApplication(), true)
        try {
            compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
                PlanningScreen(workspace, false, requestPermission={})
            } } }
            compose.onNodeWithTag("planning-tab-Routines").performClick()
            compose.onNodeWithText("Latest execution: completed · Queued for delivery").assertExists()
            compose.onNodeWithText("Results").performScrollTo().performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithText("2026-11-24 17:45:00").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("2026-11-24 17:45:00").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithText(answer).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(answer).assertIsDisplayed()
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.method == "GET" && it.header("Authorization") == "Bearer fixture" })
            assertTrue(calls.all { it.url.queryParameter("job") == routine.id })
            assertEquals(revision, calls.last().url.queryParameter("revision"))
        } finally { workspace.close(); client.close(); scope.cancel() }
    }
}
