package org.praxisog.hermes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 6's accept criteria, as behaviour: "typing finds and launches a sideloaded app; a
 * non-matching query offers 'ask Hermes' and lands in a new session with the text
 * pre-filled."
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class SearchBehaviourTest {

    @get:Rule val compose = createComposeRule()

    private val apps = listOf(
        LaunchableApp("Organic Maps", "org.organicmaps", "org.organicmaps.Main"),
        LaunchableApp("Aegis Authenticator", "com.beemdevelopment.aegis", "com.beemdevelopment.aegis.Main"),
    )

    private var launched: LaunchableApp? = null
    private var asked: String? = null

    private fun setUp(
        query: String,
        sessions: List<org.praxisog.hermes.model.Session> = UiFixtures.sessions,
        createError: org.praxisog.hermes.model.HermesError? = null,
    ) {
        compose.setContent {
            SessionListScreen(
                state = UiState.Ready(sessions),
                now = UiFixtures.NOW,
                selectedId = null,
                onOpen = {},
                onRetry = {},
                onNewSession = {},
                createError = createError,
                query = query,
                onQueryChange = {},
                apps = apps,
                onLaunchApp = { launched = it },
                onAskHermes = { asked = it },
            )
        }
    }

    @Test fun theNewChatButtonIsLabelledForAccessibility() {
        // On the device the accessibility tree exposed the "+" icon but not "New chat", so
        // the button announced itself as a plus sign.
        setUp("")
        compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
    }

    @Test fun typingFindsAndLaunchesAnApp() {
        setUp("organic")
        compose.onNodeWithText("Organic Maps").assertIsDisplayed()
        compose.onNodeWithText("Organic Maps").performClick()
        assertEquals(apps.first(), launched)
    }

    @Test fun nonMatchingQueryOffersHermesAndHandsOverTheText() {
        setUp("what is the tailnet IP of the fold")
        compose.onNodeWithText("Ask Hermes").assertIsDisplayed()
        compose.onNodeWithText("No apps or sessions match. Ask Hermes instead.").assertIsDisplayed()
        compose.onNodeWithText("Ask Hermes").performClick()
        assertEquals("what is the tailnet IP of the fold", asked)
    }

    @Test fun hermesIsOfferedEvenWhenAnAppMatches() {
        // A query that collides with an app name is still a perfectly good prompt; hiding the
        // option in that case would make the feature feel arbitrary.
        setUp("organic")
        compose.onNodeWithText("Organic Maps").assertIsDisplayed()
        compose.onNodeWithText("Ask Hermes").assertIsDisplayed()
    }

    @Test fun matchingSessionsAreAlsoSearchable() {
        setUp("widget")
        compose.onNodeWithText("Widget cold-start timings").assertIsDisplayed()
    }

    @Test fun anEmptyQueryShowsTheSessionListNotSearchResults() {
        setUp("")
        compose.onNodeWithText("Ask Hermes").assertDoesNotExist()
        compose.onNodeWithText("Widget cold-start timings").assertIsDisplayed()
    }

    @Test fun aFailedFirstChatShowsItsErrorOnAnEmptyList() {
        setUp("", sessions = emptyList(), createError = UiFixtures.authFailure)
        compose.onNodeWithText(UiFixtures.authFailure.toUserMessage()).assertIsDisplayed()
        compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
    }

    @Test fun aFailedAskHermesShowsItsErrorAboveSearchResults() {
        setUp("what is the tailnet IP", createError = UiFixtures.authFailure)
        compose.onNodeWithText(UiFixtures.authFailure.toUserMessage()).assertIsDisplayed()
        compose.onNodeWithText("Ask Hermes").assertIsDisplayed()
    }
}
