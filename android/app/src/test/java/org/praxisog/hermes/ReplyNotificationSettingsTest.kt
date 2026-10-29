package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyNotificationSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun labelAndSwitchShareOneAccessibleToggleAndChangeStateOncePerTap() {
        var changes = 0
        compose.setContent { var checked by remember { mutableStateOf(false) }
            HermesTheme { Column { ReplyNotificationSettings(checked, false, null, { checked = it; changes++ }, {}) } }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithText("Reply notifications").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { assertEquals(1, changes) }
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.runOnIdle { assertEquals(2, changes) }
    }
    @Test fun permissionDenialLeavesAVisibleReasonAndWorkingSettingsRecovery() {
        var opened = false
        compose.setContent { HermesTheme { Column { ReplyNotificationSettings(false, false, NOTIFICATIONS_BLOCKED, {}, { opened = true }) } } }
        compose.onNodeWithText(NOTIFICATIONS_BLOCKED).assertIsDisplayed()
        compose.onNodeWithText("Android notification settings").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
