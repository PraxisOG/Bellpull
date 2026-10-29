package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

/** No personal server, default-assistant change, real alarm, call or message. */
class PhoneAssistantDeviceTest {
    private val ui = DeviceFixtureUi
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun assistantEntryPreservesInputAcrossReentryAndRecreation() = ui.withWindows {
        assertTrue("Use an unconfigured fixture", SecureConnectionStore(context).profiles().isEmpty())
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ActivityScenario.launch<MainActivity>(intent).use { scene ->
            ui.await("assistant panel") { ui.text("Bellpull assistant") != null }
            ui.await("request field") { ui.find { it.isEditable } != null }
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "alarm for 7") }
            assertTrue(ui.find { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            ui.tapText("Go")
            ui.await("ambiguous alarm clarification") { ui.text("AM or PM? Try ‘alarm for 7:30 am’.") != null }
            // ActivityScenario adds CLEAR_TASK to its launch Intent. Never reuse that flag
            // when simulating a second system invocation; it deliberately destroys the draft.
            scene.onActivity { it.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            ui.await("draft after repeated assist") { ui.find { it.isEditable && it.text?.contains("alarm for 7") == true } != null }
            scene.recreate()
            ui.await("draft after recreation") { ui.find { it.isEditable && it.text?.contains("alarm for 7") == true } != null }
            ui.screenshot("phase15-assistant")
            ui.tapText("Close")
            ui.await("panel closed") { ui.text("Bellpull assistant") == null }
            scene.recreate()
            ui.await("normal app restored") { ui.text("Bellpull") != null }
            assertNull(ui.text("Bellpull assistant"))
        }
    }
    @Test fun localHandoffCreatesANewSavedDraftWithoutOverwritingThePreviousChat() = ui.withWindows {
        val profile = "phase15-fixture"
        val workspace = OfflineWorkspace(context, profile) { null }
        try {
            ui.await("workspace ready") { !workspace.state.value.busy }
            workspace.create(null, initialDraft = "Existing words")
            ui.await("first chat saved") { !workspace.state.value.busy }
            val previous = workspace.state.value.current!!.id
            workspace.create(null, initialDraft = "New assistant request")
            ui.await("assistant chat saved") { !workspace.state.value.busy }
            assertNotEquals(previous, workspace.state.value.current!!.id)
            assertEquals("New assistant request", workspace.state.value.current!!.draft)
            assertEquals("Existing words", OfflineStorage(context, profile).fork(previous)!!.draft)
            assertTrue(workspace.state.value.current!!.turns.isEmpty())
        } finally { workspace.close() }
    }
}
