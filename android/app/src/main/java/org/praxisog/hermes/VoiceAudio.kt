package org.praxisog.hermes

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface VoiceOutput {
    fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit)
    fun speak(text: String, started: () -> Unit, complete: () -> Unit)
    fun stop()
    fun close()
}
/** Only voices explicitly reported as offline by the installed engine are eligible. */
internal class AndroidVoiceOutput(context: Context, private val rate: Float) : VoiceOutput {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var generation = 0L
    private var done: (() -> Unit)? = null
    private var begun: (() -> Unit)? = null
    private var failure: (String) -> Unit = {}
    private var closed = false
    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) {
        failure = onError
        engine = TextToSpeech(context) { result -> main.post {
            if (closed) return@post
            val tts = engine ?: return@post
            if (result != TextToSpeech.SUCCESS) { onError("Speech output could not start. Check your installed TTS engine."); return@post }
            val voices = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
            val choice = voices.firstOrNull { it.name == tts.defaultVoice?.name } ?: voices.firstOrNull()
            if (choice == null) { onError("Install an offline voice for your language. No cloud fallback was selected."); return@post }
            if (tts.setVoice(choice) != TextToSpeech.SUCCESS) { onError("The installed offline voice could not load."); return@post }
            tts.setSpeechRate(rate)
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { main.post { if (utteranceId == generation.toString()) begun?.invoke() } }
                override fun onDone(utteranceId: String?) { main.post { if (utteranceId == generation.toString()) { val callback = done; done = null; callback?.invoke() } } }
                @Deprecated("Required platform callback") override fun onError(utteranceId: String?) { main.post {
                    if (utteranceId == generation.toString()) { stop(); failure("Speech playback failed. The written answer is still available.") }
                } }
                override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
            })
            onReady(choice.name)
        } }
    }
    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) {
        generation++; done = complete; begun = started
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, generation.toString()) != TextToSpeech.SUCCESS) {
            done = null; failure("Speech playback failed. The written answer is still available.")
        }
    }
    override fun stop() { generation++; done = null; begun = null; engine?.stop() }
    override fun close() { closed = true; stop(); engine?.shutdown(); engine = null }
}

internal interface VoiceCapture {
    fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit): Boolean
    fun close()
}
internal class AndroidVoiceCapture(private val scope: CoroutineScope) : VoiceCapture {
    private var recorder: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private val active = AtomicBoolean(false)
    private val generation = AtomicInteger()
    var echoCancellation = false
        private set
    @SuppressLint("MissingPermission")
    override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit): Boolean {
        val token = generation.incrementAndGet()
        echoCancellation = false
        return runCatching {
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val next = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(maxOf(minimum * 2, 6400)).build()
            recorder = next
            check(next.state == AudioRecord.STATE_INITIALIZED)
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(next.audioSessionId)?.also {
                echoCancellation = it.setEnabled(true) == 0
            }
            if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(next.audioSessionId)?.also { it.enabled = true }
            next.startRecording(); check(next.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            active.set(true)
            scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(320)
                while (isActive && active.get() && token == generation.get()) {
                    val count = next.read(buffer, 0, buffer.size)
                    if (!active.get() || token != generation.get()) break
                    if (count <= 0) { withContext(Dispatchers.Main) { if (token == generation.get()) error("Microphone capture stopped. Resume voice when ready.") }; break }
                    val copy = buffer.copyOf(count)
                    withContext(Dispatchers.Main) { if (active.get() && token == generation.get()) frame(copy, count) }
                }
            }
            true
        }.getOrElse { close(); error("Microphone is unavailable. Check permission, privacy controls and other recording apps."); false }
    }
    override fun close() {
        generation.incrementAndGet(); active.set(false)
        runCatching { recorder?.stop() }
        echo?.release(); echo = null; noise?.release(); noise = null
        recorder?.release(); recorder = null
    }
}
