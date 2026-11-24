package org.praxisog.hermes

import android.Manifest
import android.app.AlertDialog
import android.net.Uri
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.util.TypedValue
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*

internal fun canDictate(input: Int): Boolean {
    val variation = input and InputType.TYPE_MASK_VARIATION
    return when (input and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> variation !in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        InputType.TYPE_CLASS_NUMBER -> variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
        InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }
}

/** A tap starts a bounded local capture. Only its original editor can receive the final text. */
class DictationInputMethod : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = AndroidVoiceCapture(scope)
    private val gate = VoiceGate()
    private var transcriber: VoiceTranscriber? = null
    private var recognizing: Job? = null
    private var generation = 0L
    private var listening = false
    private lateinit var action: Button
    private lateinit var status: TextView
    private val runtime get() = BellpullRuntime.get(applicationContext)

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        status = TextView(this)
        action = Button(this).apply { setOnClickListener { if (listening) finishRecording(gate.finish()) else startRecording() } }
        val switch = Button(this).apply {
            text = "Switch keyboard"
            setOnClickListener { cancel(); getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val background = TypedValue()
            if (theme.resolveAttribute(android.R.attr.colorBackground, background, true)) {
                if (background.resourceId != 0) setBackgroundResource(background.resourceId)
                else setBackgroundColor(background.data)
            }
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                view.setPadding(pad + bars.left, pad, pad + bars.right, pad + bars.bottom)
                insets
            }
            addView(status)
            addView(LinearLayout(context).apply { addView(action); addView(switch) })
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        cancel()
        super.onStartInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
    }

    private fun render(message: String? = null) {
        if (!::action.isInitialized) return
        val allowed = canDictate(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL)
        status.text = message ?: when {
            !allowed -> "Dictation unavailable in this field"
            listening -> "Listening on this device"
            recognizing?.isActive == true -> "Transcribing on this device"
            else -> "Bellpull Dictation"
        }
        action.text = if (listening) "Insert dictation" else "Dictate"
        action.isEnabled = allowed && recognizing?.isActive != true
    }

    private fun startRecording() {
        if (!canDictate(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL) || currentInputConnection == null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, DictationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (runtime.voice != null) { render("End the active Bellpull voice session first."); return }
        val lab = runtime.speechLab
        val problem = lab.voiceStartProblem()
        if (problem != null) { render(problem); return }
        transcriber = runCatching { lab.voiceTranscriber() }.getOrElse { render(it.message); return }
        listening = true
        render()
        capture.start({ pcm, count ->
            val frame = gate.accept(pcm, count, hold = true, manual = true)
            if (!gate.active) finishRecording(frame.complete)
        }, { error -> cancel(); render(error) })
    }

    private fun finishRecording(wav: ByteArray?) {
        if (!listening) return
        listening = false
        capture.close()
        val engine = transcriber ?: return
        if (wav == null) { cancel(); render("No speech detected. Tap Dictate to try again."); return }
        val token = generation
        val editor = currentInputConnection
        recognizing = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) { engine.transcribe(wav) }
                if (token != generation || editor == null || editor !== currentInputConnection || !isInputViewShown) return@launch
                val words = result.getOrThrow()
                recognizing = null
                val notice = when {
                    words.isBlank() -> "No speech detected. Tap Dictate to try again."
                    !editor.commitText(words, 1) -> "This field could not insert the dictation."
                    else -> null
                }
                render(notice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recognizing = null
                render(failure.message ?: "Local transcription failed. Try again or switch keyboards.")
            } finally {
                engine.close()
                if (token == generation) {
                    transcriber = null
                    recognizing = null
                    action.isEnabled = canDictate(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL)
                }
            }
        }
        recognizing?.start()
        render()
    }

    private fun cancel() {
        generation++
        listening = false
        capture.close()
        transcriber?.close()
        transcriber = null
        recognizing?.cancel()
        recognizing = null
        gate.reset()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) { cancel(); super.onFinishInputView(finishingInput) }
    override fun onFinishInput() { cancel(); super.onFinishInput() }
    override fun onWindowHidden() { cancel(); super.onWindowHidden() }
    override fun onDestroy() { cancel(); scope.cancel(); super.onDestroy() }
}

/** Android requires a visible activity for the runtime permission dialog. */
class DictationPermissionActivity : ComponentActivity() {
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) finish()
        else AlertDialog.Builder(this).setMessage("Allow microphone access in Bellpull’s app settings to use dictation.")
            .setPositiveButton("App settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                finish()
            }.setNegativeButton("Close") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
}
