package org.praxisog.hermes

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.praxisog.hermes.model.ConversationProject
import com.github.takahirom.roborazzi.captureScreenRoboImage

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h420dp")
class BellpullDialogUiTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "w780dp-h120dp")
    fun tinyKeyboardViewportStillLetsTheUserReachSave() {
        var saved = false
        compose.setContent { HermesTheme {
            ProjectEditor(ConversationProject("a".repeat(32), "Tiny viewport"), true, true, null, { saved = true }, {})
        } }
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        captureScreenRoboImage()
        compose.onNodeWithText("Save").performTouchInput { click() }
        assertTrue(saved)
    }

    @Test fun outsideTapDismissesButFormTextAndButtonsDoNotFallThrough() {
        var open by mutableStateOf(true)
        var saved = false
        compose.setContent { HermesTheme {
            if (open) BellpullTextDialog(
                onDismissRequest = { open = false },
                title = { Text("Edit fixture") }, text = { Text("Form content") },
                confirmButton = { TextButton(onClick = { saved = true }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            )
        } }
        compose.onNodeWithText("Form content").performTouchInput { click() }
        assertTrue(open)
        compose.onNodeWithText("Save").performTouchInput { click() }
        assertTrue(saved)
        assertTrue(open)
        compose.onNode(isDialog()).performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithText("Edit fixture").assertDoesNotExist()
        assertFalse(open)
    }
}
