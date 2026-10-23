package org.praxisog.hermes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ThinkingMenuScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val id = "thinking-menu-fixture"
    private val original = ModelSelection("qwen38-flash-next", "custom", ModelOptions(ReasoningChoice(effort = "medium")))
    private val caps = ReasoningCapabilities(listOf("low", "medium", "xhigh"), canDisable = true, canEnable = true)
    private val writes = CopyOnWriteArrayList<ModelSelection>()
    private lateinit var workspace: Phase3Workspace
    private lateinit var client: HermesClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun app(capabilities: ReasoningCapabilities = caps, reject: Boolean = false) {
        val context = RuntimeEnvironment.getApplication() as Context
        val cache = SessionCache(context, id, context.getSharedPreferences(id, Context.MODE_PRIVATE))
        val catalog = ModelCatalog(1, listOf(ModelRoute(original.model, original.provider, true, reasoning = capabilities)), original)
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val body = when (request.url.encodedPath) {
                "/api/bellpull/models" -> Json.encodeToString(catalog)
                "/api/bellpull/sessions/$id/model" -> {
                    if (request.method == "PUT") {
                        val sink = Buffer(); request.body!!.writeTo(sink)
                        writes += Json.decodeFromString<ModelSelection>(sink.readUtf8())
                        if (reject) status = 409
                    }
                    if (status == 409) """{"error":{"message":"Fixture selection rejected"}}"""
                    else Json.encodeToString(SessionModel(id, writes.lastOrNull() ?: original))
                }
                else -> error("Unexpected request: ${request.method} ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        client = HermesClient("http://fixture.invalid", { "synthetic-fixture-key" }, httpClient = http)
        workspace = Phase3Workspace(context, id, scope, client, cache, filesSupported = false, modelsSupported = true)
        compose.setContent { CompositionLocalProvider(LocalRippleConfiguration provides null) {
            HermesApp(sessions = UiState.Ready(listOf(Session(id, title = "Thinking controls"))),
                history = UiState.Ready(listOf(Message(1, role = "user", content = "Plan a quiet afternoon."))),
                turn = TurnState.Idle, openSessionId = id, openSession = Session(id, title = "Thinking controls"), now = UiFixtures.NOW,
                onOpenSession = {}, onCloseSession = {}, onSend = { false }, onStop = {},
                onRetrySessions = {}, onRetryHistory = {}, onNewSession = {},
                thinkingMenu = { close -> ConversationThinkingMenu(workspace, id, close) },
                attachmentStrip = { AttachmentStrip(workspace, id) })
        } }
    }

    @After fun close() {
        if (::workspace.isInitialized) workspace.close()
        scope.cancel()
        if (::client.isInitialized) client.close()
    }

    private fun openThinking() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Thinking level").performClick()
        awaitOptions()
    }

    private fun awaitState(condition: () -> Boolean) {
        // IO replies resume the workspace on Android's main looper. Pump that
        // looper as well as the Compose frame clock while awaiting the response.
        compose.waitUntil(5_000) { compose.runOnIdle(condition) }
    }

    private fun awaitOptions() {
        compose.waitForIdle()
        awaitState { workspace.errors.isNotEmpty() || (workspace.selections[id] != null && workspace.catalog != null) }
        assertTrue("Options unavailable: ${workspace.errors}; selected=${workspace.selections[id]}; catalog=${workspace.catalog}", workspace.selections[id] != null && workspace.catalog != null)
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/$name.png")
    }

    private fun selectAndCapture(prefix: String) {
        app()
        compose.onNodeWithContentDescription("More options").performClick()
        capture("$prefix-overflow")
        compose.onNodeWithText("Thinking level").performClick()
        awaitOptions()
        for (label in listOf("Default", "Off", "On", "Low", "Medium", "XHigh")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("Medium").assertIsSelected()
        compose.onNodeWithText("High").assertDoesNotExist()
        capture("$prefix-levels")
        compose.onNodeWithText("Low").performClick()
        compose.onNodeWithText("Thinking level").assertDoesNotExist()
        compose.onNodeWithText("Session info").assertDoesNotExist()
        awaitState { workspace.selections[id]?.options?.reasoning?.effort == "low" }
        assertEquals(listOf(original.copy(options = ModelOptions(ReasoningChoice(effort = "low")))), writes)
        openThinking()
        compose.onNodeWithText("Low").assertIsSelected()
        compose.onNodeWithText("Default").performClick()
        compose.onNodeWithText("Thinking level").assertDoesNotExist()
        awaitState { workspace.selections[id]?.options?.reasoning == null }
        assertEquals(original.copy(options = ModelOptions()), writes.last())
    }

    @Test fun coverMenuSavesAndDismisses() = selectAndCapture("thinking-cover")
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun unfoldedMenuSavesAndDismisses() = selectAndCapture("thinking-unfolded-dark")

    @Test fun unsupportedControlsStayHiddenAndSubmenuCanGoBack() {
        app(ReasoningCapabilities())
        openThinking()
        for (label in listOf("Off", "On", "Low", "Medium", "XHigh")) compose.onNodeWithText(label).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to more options").performClick()
        compose.onNodeWithText("Session info").assertIsDisplayed()
        assertTrue(writes.isEmpty())
    }

    @Test fun rejectedSaveClosesMenusButKeepsTheSelectionAndShowsAnInlineError() {
        app(reject = true)
        openThinking()
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Thinking level").assertDoesNotExist()
        awaitState { workspace.errors[id] != null }
        assertEquals(original, workspace.selections[id])
        compose.onNodeWithText(workspace.errors.getValue(id)).assertIsDisplayed()
    }
}
