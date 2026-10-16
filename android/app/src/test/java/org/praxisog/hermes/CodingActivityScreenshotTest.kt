package org.praxisog.hermes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@Config(sdk=[35],qualifiers="w393dp-h850dp")
class CodingActivityScreenshotTest {
    @get:Rule val compose=createComposeRule()
    private val work=CodingWork("a".repeat(32),"root","run_one","proc_one","ses_example",state="running",
        workspaceId="a".repeat(32),workspaceLabel="/work/bellpull",note="CLI text and tools appear when their parts finish.")
    private val snapshot=CodingSnapshot(1,"root",listOf("root"),2,true,true,works=listOf(work),rows=listOf(
        CodingRow(1,work.id,"tool",123,"tool","bash","All 12 checks passed.",command="./gradlew test",outcome="completed"),
        CodingRow(2,work.id,"text",124,"text","OpenCode response","The parser is repaired. I’m checking reconnect recovery next.")),observedAt=1.0)
    private fun show(disconnected: Boolean=false, supported: Boolean=true) {
        var stops=0; var chats=0
        compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
            CodingActivityPanel(snapshot,false,supported,disconnected,null,"Running",{}, {},{ chats++ },{ stops++ },true,Modifier.fillMaxSize())
        } } }
        compose.onNodeWithText("▸ bash · completed").performClick()
        compose.onNodeWithText("./gradlew test").assertIsDisplayed()
        compose.onNodeWithText("All 12 checks passed.").assertIsDisplayed()
        compose.onNodeWithTag("coding-gap").assertIsDisplayed()
        captureScreenRoboImage()
        compose.onNodeWithText("Stop Hermes").performClick(); assertEquals(1,stops)
        compose.onNodeWithText("Chat / follow up").performClick(); assertEquals(1,chats)
        compose.onNodeWithText("Allow once").assertDoesNotExist()
    }
    @Test fun activityCover() = show()
    @Test @Config(qualifiers="w840dp-h900dp-night") fun activityUnfolded() = show()
    @Test fun disconnectedActivityCover() = show(disconnected=true)
}
