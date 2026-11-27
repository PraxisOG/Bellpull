package org.praxisog.hermes

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Enable the fixture IME and install the local model on the disposable emulator first. */
class DictationImeDeviceTest {
    private val ui = DeviceFixtureUi
    private fun key(label: String) = ui.find(AccessibilityWindowInfo.TYPE_INPUT_METHOD) { node ->
        node.text?.toString().equals(label, ignoreCase = true) && node.isVisibleToUser &&
            !Rect().also(node::getBoundsInScreen).isEmpty
    }

    private var priorIme: String? = null

    private fun selectIme(component: String) {
        check(component.matches(Regex("[a-zA-Z0-9_./]+")))
        ParcelFileDescriptor.AutoCloseInputStream(ui.automation.executeShellCommand("ime set $component")).use { result ->
            check(result.bufferedReader().readText().contains("selected"))
        }
    }

    @Before fun selectDictation() {
        ui.guard()
        val context = ApplicationProvider.getApplicationContext<Context>()
        priorIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val component = "${context.packageName}/${DictationInputMethod::class.java.name}"
        if (priorIme == component) {
            // Starting instrumentation restarts this process and invalidates the old service binder.
            val other = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.first { it.id != component }
            selectIme(other.id)
        }
        selectIme(component)
    }

    @After fun restoreKeyboard() { priorIme?.let(::selectIme) }

    @Test fun switchesEditorsAndCommitsOnlyToTheCurrentNonPasswordField() = ui.withWindows {
        val audio = InstrumentationRegistry.getArguments().getString("injectAudio") == "true"
        lateinit var words: EditText
        lateinit var password: EditText
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity ->
                words = EditText(activity).apply { setSingleLine(); setText("Before  after"); setSelection(7) }
                password = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                activity.setContentView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setOnApplyWindowInsetsListener { view, insets ->
                        view.setPadding(0, insets.getInsets(android.view.WindowInsets.Type.statusBars()).top, 0, 0)
                        insets
                    }
                    addView(words)
                    addView(password)
                })
                words.requestFocus()
            }
            ui.awaitMain("Fixture editor never gained focus") { words.hasWindowFocus() }
            ui.tap(checkNotNull(ui.find { it.isEditable && it.text.toString().startsWith("Before") }))
            scene.onActivity { words.windowInsetsController?.show(android.view.WindowInsets.Type.ime()) }
            ui.await("Dictation controls must be visible and tappable") {
                key("Dictate")?.isEnabled == true && key("Switch keyboard")?.isEnabled == true
            }
            ui.tap(checkNotNull(key("Dictate")))
            ui.await("Microphone did not start") { key("Insert dictation") != null }
            scene.onActivity { password.requestFocus() }
            ui.await("Dictation remained active in password editor") {
                key("Dictation unavailable in this field") != null && key("Dictate")?.isEnabled == false
            }
            scene.onActivity {
                assertEquals("Before  after", words.text.toString())
                assertTrue(password.text.isEmpty())
                words.requestFocus(); words.setSelection(7)
            }
            ui.await("Ordinary text field stayed disabled") { key("Dictate")?.isEnabled == true }
            if (audio) {
                ui.tap(checkNotNull(key("Dictate")))
                ui.await("Listening status missing") { key("Listening on this device") != null }
                SystemClock.sleep(7000) // Capture at least one full replay of the public/synthetic sample.
                ui.tap(checkNotNull(key("Insert dictation")))
                val start = SystemClock.elapsedRealtime()
                ui.awaitMain("Local words not committed at cursor") {
                    val text = words.text.toString()
                    text.startsWith("Before ") && text.endsWith(" after") && text.length > "Before  after".length
                }
                scene.onActivity {
                    assertTrue(password.text.isEmpty())
                    val result = words.text.toString().removePrefix("Before ").removeSuffix(" after")
                    val expected = InstrumentationRegistry.getArguments().getString("expectedWord")
                    if (expected != null) assertTrue("Expected fixture word in: $result", result.contains(expected, ignoreCase = true))
                    ui.instrumentation.sendStatus(0, Bundle().apply {
                        putLong("commit_ms", SystemClock.elapsedRealtime() - start)
                        putInt("inserted_characters", result.length)
                    })
                }
            }
        }
    }

    /** Host replays the pinned speech-lab WAV for each request through the emulator microphone. */
    @Test fun comparesKeyboardAndComposerWithTheSameSpeech() = ui.withWindows {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("dictationParity") == "true")
        val references = JSONObject(String(Base64.decode(requireNotNull(args.getString("parityReferences")),
            Base64.DEFAULT), Charsets.UTF_8))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val runtime = BellpullRuntime.get(app)
        val secure = SecureConnectionStore(app)
        ui.main { runtime.disconnect() }
        secure.clear()
        val profile = secure.add("Dictation fixture", "http://127.0.0.1:18646", "bellpull-stock-device-fixture")
        secure.activate(profile.id)
        val completed = File(app.filesDir, "parity-playback-done")
        val settings = VoiceSettings(app, profile.id)
        settings.saveRoute("phone_fixture", ConversationVoiceRoute.LOCAL)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                ui.awaitMain("Fixture gateway did not list the chat") {
                    (runtime.store?.sessions as? UiState.Ready)?.value?.any { it.id == "phone_fixture" } == true
                }
                if (ui.text("Stock file sharing fixture") == null) {
                    ui.find { it.contentDescription?.toString() in setOf("Back to sessions", "Show sessions") }?.let(ui::tap)
                }
                ui.tapText("Stock file sharing fixture")
                ui.awaitMain("Fixture history not ready") {
                    runtime.store?.openSessionId == "phone_fixture" && runtime.store?.history is UiState.Ready
                }
                val store = checkNotNull(runtime.store)
                val cases = listOf("clean", "numbers-measurements", "natural-pauses", "road-noise")
                val passes = args.getString("parityPasses")?.toInt() ?: 2
                require(passes in 1..3)
                repeat(passes) { pass ->
                    cases.forEachIndexed { index, sample ->
                        val routes = if ((pass + index) % 2 == 0) listOf("ime", "app") else listOf("app", "ime")
                        for (route in routes) {
                            val cold = pass == 0 && index == 0
                            if (cold) runtime.speechLab.runtime.close()
                            ui.main { store.updateDraft(TextFieldValue("")) }
                            completed.delete()
                            val request = "$pass/$sample/$route"
                            val start = replayAndRelease(scene, runtime, route, request, completed)
                            ui.awaitMain("No draft text for $request") { store.draft.text.isNotBlank() }
                            val elapsed = SystemClock.elapsedRealtime() - start
                            ui.main {
                                ui.instrumentation.sendStatus(0, Bundle().apply {
                                    val errors = wordErrors(references.getString(sample), store.draft.text)
                                    putString("parity_sample", JSONObject().put("request", request)
                                        .put("word_errors", errors.edits).put("reference_words", errors.referenceWords)
                                        .put("cold", cold).put("release_to_draft_ms", elapsed)
                                        .put("model", runtime.speechLab.selected.id).put("transcript", store.draft.text).toString())
                                })
                            }
                        }
                    }
                }
            }
        } finally {
            completed.delete()
            ui.main { runtime.voice?.close(); runtime.disconnect() }
            settings.clear()
            secure.clear()
        }
    }

    private fun replayAndRelease(scene: ActivityScenario<MainActivity>, runtime: BellpullRuntime,
        route: String, request: String, completed: File): Long {
        val keyboard = route == "ime"
        val stopControl: AccessibilityNodeInfo
        if (keyboard) {
            ui.await("Composer edit field missing") { ui.find { it.isEditable } != null }
            ui.tap(checkNotNull(ui.find { it.isEditable }))
            ui.await("Dictate key missing") { key("Dictate")?.isEnabled == true }
            ui.tap(checkNotNull(key("Dictate")))
            ui.await("IME did not start listening") { key("Listening on this device") != null }
            stopControl = checkNotNull(key("Insert dictation"))
        } else {
            scene.onActivity { it.window.insetsController?.hide(WindowInsets.Type.ime()) }
            ui.await("Keyboard did not hide") { key("Dictate") == null }
            fun mic() = ui.find { it.contentDescription == "Voice conversation" && it.isEnabled }
            ui.await("Composer microphone missing") { mic() != null }
            stopControl = checkNotNull(mic())
        }
        val bounds = Rect().also(stopControl::getBoundsInScreen)
        var downAt = 0L
        fun touch(action: Int) {
            if (action == MotionEvent.ACTION_DOWN) downAt = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(ui.automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_UP) downAt = 0L
        }
        try {
            if (!keyboard) {
                touch(MotionEvent.ACTION_DOWN)
                ui.awaitMain("Composer microphone did not start") {
                    runtime.voice?.let { it.dictationToDraft && it.state in setOf(VoiceState.LISTENING, VoiceState.RECORDING) } == true
                }
            }
            ui.instrumentation.sendStatus(0, Bundle().apply { putString("play_clip", request) })
            ui.await("Missing audio completion for $request", 45000) { completed.exists() }
            if (keyboard) { touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(60) }
            val start = SystemClock.elapsedRealtime()
            touch(MotionEvent.ACTION_UP)
            return start
        } finally {
            if (downAt != 0L) touch(MotionEvent.ACTION_CANCEL)
        }
    }

    @Test fun dictatesIntoAndroidSettingsSearch() = ui.withWindows {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("injectAudio") == "true" && args.getString("externalEditor") == "settings")
        val expected = requireNotNull(args.getString("expectedWord"))
        ParcelFileDescriptor.AutoCloseInputStream(ui.automation.executeShellCommand(
            "am start -W --activity-clear-top -a android.settings.SETTINGS")).use { it.readBytes() }
        try {
            fun searchButton() = ui.find { it.viewIdResourceName == "com.android.settings:id/search_action_bar" }
            fun searchEditor() = ui.find { it.isEditable && it.packageName?.toString()?.startsWith("com.android.settings") == true }
            ui.await("Settings search unavailable") { searchButton() != null || searchEditor() != null }
            searchButton()?.let(ui::tap)
            ui.await("Search field did not open") { searchEditor() != null }
            ui.tap(checkNotNull(searchEditor()))
            ui.await("System search did not show Bellpull") { key("Dictate")?.isEnabled == true }
            ui.tap(checkNotNull(key("Dictate")))
            ui.await("External editor did not start capture") { key("Listening on this device") != null }
            SystemClock.sleep(7000)
            ui.tap(checkNotNull(key("Insert dictation")))
            ui.await("Expected fixture word in system search") {
                ui.find { it.isEditable && it.packageName?.toString()?.startsWith("com.android.settings") == true &&
                    it.text?.toString()?.contains(expected, true) == true } != null
            }
        } finally {
            ui.automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }
}
