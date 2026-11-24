package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Enable the fixture IME and install the local model on the disposable emulator first. */
class DictationImeDeviceTest {
    private val ui = DeviceFixtureUi
    private fun key(label: String) = ui.find(AccessibilityWindowInfo.TYPE_INPUT_METHOD) { it.text?.toString().equals(label, ignoreCase = true) }

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
            ui.await("Dictation keyboard missing") { key("Dictate")?.isEnabled == true }
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
