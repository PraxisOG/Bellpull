package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.model.InboxEntry
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="w393dp-h850dp")
class InboxScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousZone: java.util.TimeZone
    @Before fun setZone() { previousZone=java.util.TimeZone.getDefault(); java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")) }
    @After fun restoreZone() { java.util.TimeZone.setDefault(previousZone) }
    private fun checkInbox() {
        val marks = mutableListOf<Pair<String,Boolean>>()
        compose.setContent { HermesTheme { Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                InboxContent(listOf(InboxEntry("reply:chat:2", "reply", "Saved reply", "Available offline", 1792285200000, "chat")),
                    { id, dismissed -> marks.add(id to dismissed) }, {})
            }
        } } }
        compose.onRoot().captureRoboImage()
        compose.onNodeWithText("Read saved item").performClick()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.onNodeWithText("Dismiss inbox item").performClick()
        assertEquals(listOf("reply:chat:2" to false, "reply:chat:2" to true), marks)
    }
    @Test fun coverActionsAreDistinct() = checkInbox()
    @Test @Config(qualifiers="w840dp-h900dp-night") fun unfoldedActionsAreDistinct() = checkInbox()
}
