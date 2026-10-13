package org.praxisog.hermes

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.praxisog.hermes.model.*
import org.praxisog.hermes.core.HermesClient

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ProjectsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val project = ConversationProject("a".repeat(32), "WALL·E & EVE", "Finish both costumes before October 31.\nKeep electronics and sewing notes together.", "#59091E", "craft")
    private val archived = ConversationProject("b".repeat(32), "Garden lights", archived = true)
    private val sessions = listOf(Session("tip", title = "Costume wiring reference", source = "cli", lastActive = UiFixtures.NOW - 9000,
        preview = "Pinout and battery notes", messageCount = 12),
        Session("recent", title = "EVE arm pattern", source = "api_server", lastActive = UiFixtures.NOW - 60, preview = "Try the revised template", messageCount = 8),
        Session("outside", title = "Dinner plans", source = "tui", lastActive = UiFixtures.NOW))
    private val snapshot = ProjectSnapshot(revision = 3, projects = listOf(project, archived), memberships = listOf(
        ProjectMembership("root", listOf("root", "tip"), project.id, true), ProjectMembership("recent", projectId = project.id)))
    private val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder()
        .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(Json { encodeDefaults = true }.encodeToString(snapshot).toResponseBody("application/json".toMediaType())).build() }.build())
    private fun workspace(selected: String = ALL_PROJECT_CHATS, supported: Boolean = true): ProjectWorkspace {
        val result = ProjectWorkspace(scope, client, object : ProjectCache {
            override fun readProjects() = snapshot
            override fun projectScope() = selected
        }, supported)
        if (supported) result.refresh()
        return result
    }
    @After fun close() { scope.cancel(); client.close() }
    private fun panel(selected: String, supported: Boolean = true) {
        val workspace = workspace(selected, supported)
        compose.setContent { HermesTheme(palette = Palette.MAROON) { Surface {
            ProjectsPanel(workspace, sessions, UiFixtures.NOW, setOf("root"), mapOf("recent" to "Running"), setOf("recent"),
                workspace::choose, {}, {}, {}, {}, {})
        } } }
        if (supported) {
            compose.waitUntil(5000) { workspace.fresh }
            // Background refresh can finish before Compose has applied its new snapshot.
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Showing cached organization. Reconnect and refresh to make changes.")
                    .fetchSemanticsNodes().isEmpty()
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage()
    }
    @Test fun projectsCover() = panel(ALL_PROJECT_CHATS)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun projectsUnfoldedDark() = panel(ALL_PROJECT_CHATS)
    @Test fun projectViewCover() = panel(project.id)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun projectViewUnfoldedDark() = panel(project.id)
    @Test fun cachedProjectCover() = panel(project.id, false)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun cachedProjectUnfoldedDark() = panel(project.id, false)
    private fun editor() {
        compose.setContent { HermesTheme(palette = Palette.MAROON) { ProjectEditor(project, false, true, null, {}, {}) } }
        compose.waitForIdle(); captureScreenRoboImage()
    }
    @Test fun editorCover() = editor()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun editorUnfoldedDark() = editor()
    @Test @Config(qualifiers = "w393dp-h420dp") fun editorWithLimitedHeight() {
        editor()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
    private fun about() {
        compose.setContent { HermesTheme(palette = Palette.MAROON) {
            AboutDialog(InstalledAppVersion("0.4.0", 11), listOf(
                ServerPluginVersion("Costume server", "https://fixture.invalid/p/work", "0.3.0", UiFixtures.NOW),
                ServerPluginVersion("Desktop Hermes", "https://desktop.invalid", "0.2.2", UiFixtures.NOW)), {})
        } }
        compose.waitForIdle(); captureScreenRoboImage()
    }
    @Test fun aboutCover() = about()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun aboutUnfoldedDark() = about()
    private fun organize() {
        val workspace = workspace(project.id)
        compose.setContent { HermesTheme { ProjectChatDialog(workspace, sessions.first()) {} } }
        compose.waitUntil(5000) { workspace.fresh }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Reconnect and refresh projects to make changes.")
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle(); captureScreenRoboImage()
    }
    @Test fun organizeCover() = organize()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun organizeUnfoldedDark() = organize()

    @Test fun projectViewShowsPinnedBeforeRecentAndDoesNotAutomaticallyOpenAChat() {
        val workspace = workspace()
        var opened: String? = null
        compose.setContent { HermesTheme { Surface {
            ProjectsPanel(workspace, sessions, UiFixtures.NOW, setOf("root"), emptyMap(), emptySet(), workspace::choose,
                { opened = it }, {}, {}, {}, {})
        } } }
        compose.waitUntil(5000) { workspace.fresh }
        compose.onNodeWithTag("project-${project.id}").performClick()
        assertNull(opened)
        compose.onNodeWithText(project.description).assertIsDisplayed()
        compose.onNodeWithText("Dinner plans").assertDoesNotExist()
        fun top(title: String) = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("Costume wiring reference") < top("EVE arm pattern"))
        compose.onNodeWithText("EVE arm pattern").performClick()
        assertEquals("recent", opened)
    }

    @Test fun rememberedProjectOpensItsOverviewOnCoverWithNoSelectedChat() {
        val workspace = workspace(project.id)
        compose.setContent {
            HermesApp(UiState.Ready(sessions), UiState.Ready(emptyList()), TurnState.Idle, null, null, UiFixtures.NOW,
                {}, {}, {}, {}, {}, {}, {}, projects = workspace)
        }
        compose.waitUntil(5000) { workspace.fresh }
        compose.onNodeWithText(project.description).assertIsDisplayed()
        compose.onNodeWithText("Edit project").assertIsDisplayed()
        compose.onNodeWithText("Conversations", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun searchSwitchesScopeWithoutChangingMembership() {
        compose.setContent { HermesTheme {
            WorkspaceSearch(sessions.take(2), emptyList(), UiFixtures.NOW, null, "", {}, {}, {}, {}, {}, project.name, sessions)
        } }
        compose.onNodeWithText("Costume wiring reference").assertIsDisplayed()
        compose.onNodeWithText("Dinner plans").assertDoesNotExist()
        compose.onNodeWithText("All conversations", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Dinner plans").assertIsDisplayed()
        assertEquals(project.id, snapshot.projectFor("tip"))
    }

    @Test fun folderDeletionHasExplicitHistoryPreservingConfirmation() {
        val workspace = workspace(project.id)
        compose.setContent { HermesTheme { Surface {
            ProjectsPanel(workspace, sessions, UiFixtures.NOW, emptySet(), emptyMap(), emptySet(), workspace::choose, {}, {}, {}, {}, {})
        } } }
        compose.waitUntil(5000) { workspace.fresh }
        compose.onNodeWithText("Delete project").performClick()
        compose.onNodeWithText("Removes the project folder. Its conversations return to Unfiled and keep their complete saved history.").assertIsDisplayed()
        assertEquals(project.id, workspace.snapshot.projectFor("tip"))
    }
}
