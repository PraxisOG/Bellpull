package org.praxisog.hermes

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcript
import ai.moonshine.voice.TranscriberOption
import java.io.File

internal data class SpeechTuning(val updateMs: Int = 500, val vadThreshold: Float = .5f) {
    init { require(updateMs in 200..2000 && vadThreshold.isFinite() && vadThreshold in .1f.. .9f) }
}

internal data class MoonshineLine(val id: Long, val text: String, val startSeconds: Float,
    val durationSeconds: Float, val complete: Boolean)

/** Narrow seam for testing native resource ownership without loading Android JNI on the JVM. */
internal interface MoonshineApi {
    val version: Int
    val initializationMs: Double? get() = null
    fun load(path: String, architecture: Int, options: Map<String, String>): Int
    fun free(handle: Int)
    fun createStream(handle: Int): Int
    fun start(handle: Int, stream: Int): Int
    fun add(handle: Int, stream: Int, samples: FloatArray): Int
    fun poll(handle: Int, stream: Int, force: Boolean): List<MoonshineLine>?
    fun stop(handle: Int, stream: Int): Int
    fun freeStream(handle: Int, stream: Int): Int
    fun batch(handle: Int, audio: SpeechAudio): List<MoonshineLine>?
}

internal class NativeMoonshineApi : MoonshineApi {
    override val initializationMs: Double? = synchronized(NativeMoonshineApi::class.java) {
        try {
            if (initialized) null else {
                val started = System.nanoTime()
                JNI.ensureLibraryLoaded(); initialized = true
                (System.nanoTime() - started) / 1e6
            }
        }
        catch (error: LinkageError) { throw IllegalStateException("Moonshine could not load on this device. Local speech remains selected; no audio was sent off-device.", error) }
    }
    companion object { private var initialized = false }
    override val version get() = JNI.moonshineGetVersion()
    override fun load(path: String, architecture: Int, options: Map<String, String>) =
        JNI.moonshineLoadTranscriberFromFiles(path, architecture, options.map { TranscriberOption(it.key, it.value) }.toTypedArray())
    override fun free(handle: Int) = JNI.moonshineFreeTranscriber(handle)
    override fun createStream(handle: Int) = JNI.moonshineCreateStream(handle, 0)
    override fun start(handle: Int, stream: Int) = JNI.moonshineStartStream(handle, stream)
    override fun add(handle: Int, stream: Int, samples: FloatArray) = JNI.moonshineAddAudioToStream(handle, stream, samples, 16000, 0)
    override fun poll(handle: Int, stream: Int, force: Boolean) =
        JNI.moonshineTranscribeStream(handle, stream, if (force) JNI.MOONSHINE_FLAG_FORCE_UPDATE else 0)?.copyLines()
    override fun stop(handle: Int, stream: Int) = JNI.moonshineStopStream(handle, stream)
    override fun freeStream(handle: Int, stream: Int) = JNI.moonshineFreeStream(handle, stream)
    override fun batch(handle: Int, audio: SpeechAudio) =
        JNI.moonshineTranscribeWithoutStreaming(handle, audio.samples, audio.sampleRate, 0)?.copyLines()
    private fun Transcript.copyLines() = lines.orEmpty().map {
        MoonshineLine(it.id, it.text.orEmpty(), it.startTime, it.duration, it.isComplete)
    }
}

/** Called only under SpeechModelRuntime's worker-thread lock. Fresh stream IDs for every utterance. */
internal class MoonshineSpeechEngine(model: SpeechModel, directory: File, private val tuning: SpeechTuning,
    private val api: MoonshineApi = NativeMoonshineApi()) : LocalSpeechEngine {
    private var handle = api.load(directory.absolutePath, model.architecture, linkedMapOf(
        "ort_providers" to "cpu", "transcription_interval" to (tuning.updateMs / 1000.0).toString(),
        "vad_threshold" to tuning.vadThreshold.toString(), "log_output_text" to "false", "return_audio_data" to "false"
    )).also { check(it >= 0) { "Moonshine could not load ${model.label}. Verify or re-download the model; no fallback was selected." } }
    override val actualBackend = "CPU (ONNX Runtime)"
    override val nativeInitializationMs = api.initializationMs
    override val runtimeVersion = "Moonshine Voice 0.1.5; native API ${api.version}; update_ms=${tuning.updateMs}; vad_threshold=${tuning.vadThreshold}; SDK thread defaults"
    private var stream: Int? = null
    private var listener: (SpeechUpdate) -> Unit = {}
    private val lines = linkedMapOf<Long, MoonshineLine>()
    private val completed = mutableSetOf<Long>()
    private var pendingSeconds = 0.0
    private var lastPassSeconds = 0.0

    override fun start(onUpdate: (SpeechUpdate) -> Unit) {
        check(handle >= 0) { "Local speech model is unloaded." }
        releaseStream()
        lines.clear(); completed.clear(); pendingSeconds = 0.0; lastPassSeconds = 0.0; listener = onUpdate
        val created = api.createStream(handle)
        check(created >= 0) { "Moonshine could not create a speech stream." }
        stream = created
        checked(api.start(handle, stream!!))
    }
    override fun accept(samples: FloatArray) {
        val active = checkNotNull(stream) { "Start the speech stream first." }
        checked(api.add(handle, active, samples))
        pendingSeconds += samples.size / 16000.0
        val floor = tuning.updateMs / 1000.0
        if (pendingSeconds + 1e-9 < maxOf(floor, lastPassSeconds).coerceAtMost(floor * 10)) return
        pendingSeconds = 0.0
        val began = System.nanoTime()
        update(checkNotNull(api.poll(handle, active, false)) { "Moonshine could not transcribe the stream." })
        lastPassSeconds = (System.nanoTime() - began) / 1e9
    }
    override fun finish(): String {
        val active = checkNotNull(stream) { "Start the speech stream first." }
        try {
            checked(api.stop(handle, active))
            update(checkNotNull(api.poll(handle, active, true)) { "Moonshine could not finalize the stream." })
            return text()
        } finally { releaseStream() }
    }
    override fun transcribe(audio: SpeechAudio): String {
        check(handle >= 0 && stream == null)
        require(audio.sampleRate == 16000)
        return checkNotNull(api.batch(handle, audio)) { "Moonshine could not transcribe this recording." }
            .sortedBy { it.startSeconds }.joinToString(" ") { it.text.trim() }.trim()
    }
    private fun text() = lines.values.sortedBy { it.startSeconds }.joinToString(" ") { it.text.trim() }.trim()
    private fun update(next: List<MoonshineLine>) {
        next.forEach { line ->
            val old = lines.put(line.id, line)
            if (line.text.isNotBlank()) {
                if (line.complete && completed.add(line.id))
                    listener(SpeechUpdate(line.text, true, (line.startSeconds + line.durationSeconds) * 1000.0))
                else if (!line.complete && line.text != old?.text) listener(SpeechUpdate(text()))
            }
        }
    }
    private fun checked(code: Int) { check(code >= 0) { "Moonshine speech operation failed (code $code). Retry local recognition." } }
    private fun releaseStream() { stream?.let { stream = null; checked(api.freeStream(handle, it)) }; listener = {} }
    override fun close() {
        if (handle < 0) return
        try { releaseStream() } finally { val old = handle; handle = -1; api.free(old) }
    }
}
