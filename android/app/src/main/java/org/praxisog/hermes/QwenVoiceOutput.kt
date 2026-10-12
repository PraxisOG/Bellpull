package org.praxisog.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface LocalTtsSink {
    fun onChunk(samples: FloatArray): Boolean
    fun isCancelled(): Boolean
}
internal object QwenNative {
    init { System.loadLibrary("bellpull_qwen") }
    external fun load(talker: String, codec: String): Long
    external fun free(handle: Long)
    external fun synthesize(handle: Long, text: ByteArray, speaker: String, language: String,
        temperature: Float, topP: Float, topK: Int, repetition: Float, seed: Long, maxTokens: Int, sink: LocalTtsSink): Int
}
internal interface LocalTtsEngine : AutoCloseable {
    fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int
}
internal class NativeQwenEngine(files: VoiceOutputFiles) : LocalTtsEngine {
    private var handle = QwenNative.load(java.io.File(files.directory, QwenModel.files[0].name).path,
        java.io.File(files.directory, QwenModel.files[1].name).path).also { check(it != 0L) }
    override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
        check(handle != 0L)
        config.validated()
        return QwenNative.synthesize(handle, text.toByteArray(Charsets.UTF_8), config.speaker, config.language, config.temperature,
            config.topP, config.topK, config.repetition, config.seed, 750, sink)
    }
    override fun close() { if (handle != 0L) { QwenNative.free(handle); handle = 0 } }
}

/** Never release a native context during compute. Unload also invalidates its active lease. */
internal class LocalTtsRuntime(private val verify: () -> Unit,
    private val owner: ReentrantLock = ReentrantLock(), private val beforeLoad: () -> Unit = {},
    private val create: () -> LocalTtsEngine) {
    private val lock = ReentrantLock()
    private var engine: LocalTtsEngine? = null
    private val revision = AtomicLong()
    @Volatile var loaded = false; private set
    fun <T> withModel(block: (LocalTtsEngine, Double, () -> Boolean) -> T): T {
        val lease = revision.get()
        return owner.withLock { lock.withLock {
            check(lease == revision.get()) { "Voice operation cancelled." }
            var loadMs = 0.0
            if (engine == null) {
                val start = System.nanoTime()
                beforeLoad(); verify(); engine = create(); loaded = true
                loadMs = (System.nanoTime() - start) / 1e6
            }
            block(checkNotNull(engine), loadMs) { lease != revision.get() }
        } }
    }
    fun invalidate() { revision.incrementAndGet() }
    fun unload() {
        invalidate()
        owner.withLock { lock.withLock { engine?.close(); engine = null; loaded = false } }
    }
}

internal interface VoicePcmPlayer : AutoCloseable {
    fun write(samples: FloatArray)
    suspend fun drain()
    fun stop()
}
internal class AndroidVoicePcmPlayer(rate: Float, volume: Float) : VoicePcmPlayer {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(24000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(24000, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private var frames = 0L
    @Volatile private var stopped = false
    init {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Audio output could not start." }
        track.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(.7f, 1.5f)).setPitch(1f)
        track.setVolume(volume); track.play()
    }
    override fun write(samples: FloatArray) {
        var at = 0
        while (at < samples.size && !stopped) {
            val n = track.write(samples, at, samples.size - at, AudioTrack.WRITE_BLOCKING)
            check(n > 0 || stopped) { "Audio playback stopped unexpectedly." }
            if (n > 0) { at += n; frames += n }
        }
    }
    override suspend fun drain() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 65_000
        while (!stopped && (track.playbackHeadPosition.toLong() and 0xffffffffL) < frames) {
            currentCoroutineContext().ensureActive()
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audio output did not finish. Try again." }
            delay(15)
        }
    }
    override fun stop() { stopped = true; runCatching { track.pause(); track.flush() } }
    override fun close() { stop(); track.release() }
}

/** Bounded PCM queue; generation and playback overlap. No old audio survives interruption. */
internal class LocalVoiceOutput(
    private val runtime: LocalTtsRuntime, private val config: VoiceOutputConfiguration, private val rate: Float,
    private val severe: () -> Boolean,
    private val player: (Float, Float) -> VoicePcmPlayer = ::AndroidVoicePcmPlayer,
    private val cue: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val callbacks: CoroutineDispatcher = Dispatchers.Main.immediate,
) : VoiceOutput {
    private val generation = AtomicLong()
    private var task: Job? = null
    @Volatile private var audio: VoicePcmPlayer? = null
    private var closed = false
    private var failure: (String) -> Unit = {}
    private val name: String = if (config.engine == VoiceOutputEngine.POCKET) "Pocket" else "Qwen"
    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) {
        stop(); failure = onError
        val token = generation.get()
        task = scope.launch {
            try {
                check(!severe()) { "Phone is too warm. Let it cool before using $name." }
                withContext(Dispatchers.IO) { runtime.withModel { _, _, _ -> Unit } }
                check(!severe()) { "Phone is too warm. Let it cool before using $name." }
                if (!closed && token == generation.get()) onReady("$name · ${if (config.engine == VoiceOutputEngine.POCKET) "Loona" else config.speaker} · CPU")
            } catch (error: Exception) {
                if (error !is CancellationException && !closed && token == generation.get())
                    onError(error.message ?: "$name voice could not load.")
            }
        }
    }
    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) {
        stop()
        if (closed || text.isBlank()) return
        val token = generation.get()
        task = scope.launch {
            try {
                check(!severe()) { "Phone is too warm. $name paused; let it cool before resuming." }
                coroutineScope {
                    val queue = Channel<FloatArray>(16) // Bounded native chunks; producer waits for playback.
                    var hot = false
                    val consumer = async(Dispatchers.IO) {
                        val sink = player(rate, config.volume)
                        audio = sink
                        try {
                            var first = true
                            for (samples in queue) {
                                ensureActive()
                                if (token != generation.get()) break
                                sink.write(samples)
                                if (first) { first = false; withContext(callbacks) {
                                    if (!closed && token == generation.get()) started()
                                } }
                            }
                            sink.drain()
                        } finally { if (audio === sink) audio = null; sink.close() }
                    }
                    try {
                        val status = withContext(Dispatchers.IO) {
                            val job = currentCoroutineContext()[Job]!!
                            runtime.withModel { engine, _, invalid ->
                                engine.synthesize(text, config, object : LocalTtsSink {
                                    override fun isCancelled(): Boolean {
                                        hot = severe()
                                        return !job.isActive || closed || token != generation.get() || invalid() || hot
                                    }
                                    override fun onChunk(samples: FloatArray): Boolean {
                                        if (isCancelled()) return false
                                        // Native transfers a fresh copy; queue never holds its short-lived pointer.
                                        // Backpressure also handles a decoder faster than playback.
                                        // Parent cancellation wakes this wait on interruption.
                                        return runCatching { runBlocking(job) { queue.send(samples) } }.isSuccess
                                    }
                                })
                            }
                        }
                        check(!hot) { "Phone reached severe thermal status. $name stopped; let it cool." }
                        check(status == 0) { "$name synthesis was interrupted. Try again when ready." }
                    } catch (error: Exception) { audio?.stop(); throw error }
                    finally { queue.close() }
                    consumer.await()
                }
                if (!closed && token == generation.get()) complete()
            } catch (error: Exception) {
                audio?.stop()
                if (error !is CancellationException && !closed && token == generation.get())
                    failure(error.message ?: "$name speech failed. The written answer is available.")
            }
        }
    }
    override fun chime() = cue()
    override fun stop() { generation.incrementAndGet(); task?.cancel(); task = null; audio?.stop() }
    override fun close() { closed = true; stop(); scope.cancel() }
}

internal class VoiceOutputLab(context: Context, create: (() -> LocalTtsEngine)? = null) {
    private val app = context.applicationContext
    val files = VoiceOutputFiles(app)
    val pocketFiles = VoiceOutputFiles(app, PocketModel.model)
    private val owner = ReentrantLock()
    val runtime: LocalTtsRuntime = LocalTtsRuntime(files::verify, owner, { pocketRuntime.unload() }) {
        create?.invoke() ?: NativeQwenEngine(files)
    }
    val pocketRuntime: LocalTtsRuntime = LocalTtsRuntime(pocketFiles::verify, owner, { runtime.unload() }) {
        NativePocketEngine(pocketFiles)
    }
    private val thermals = SpeechThermals(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var installed by androidx.compose.runtime.mutableStateOf(files.installed); private set
    var partial by androidx.compose.runtime.mutableStateOf(files.partial); private set
    var pocketInstalled by androidx.compose.runtime.mutableStateOf(pocketFiles.installed); private set
    var pocketPartial by androidx.compose.runtime.mutableStateOf(pocketFiles.partial); private set
    var busy by androidx.compose.runtime.mutableStateOf(false); private set
    var loaded by androidx.compose.runtime.mutableStateOf(false); private set
    var pocketLoaded by androidx.compose.runtime.mutableStateOf(false); private set
    var notice by androidx.compose.runtime.mutableStateOf<String?>(null); private set
    var progress by androidx.compose.runtime.mutableStateOf<Float?>(null); private set
    private var task: Job? = null
    private var preview: VoiceOutput? = null
    private fun modelFiles(engine: VoiceOutputEngine) = if (engine == VoiceOutputEngine.POCKET) pocketFiles else files
    private fun modelRuntime(engine: VoiceOutputEngine) = if (engine == VoiceOutputEngine.POCKET) pocketRuntime else runtime
    private fun name(engine: VoiceOutputEngine) = if (engine == VoiceOutputEngine.POCKET) "Pocket" else "Qwen"
    fun isInstalled(engine: VoiceOutputEngine) = when (engine) {
        VoiceOutputEngine.SYSTEM -> true
        VoiceOutputEngine.QWEN -> installed
        VoiceOutputEngine.POCKET -> pocketInstalled
    }
    fun startProblem(config: VoiceConfiguration): String? = when {
        busy -> "Finish the Voice output operation before starting voice."
        config.output.engine != VoiceOutputEngine.SYSTEM && !modelFiles(config.output.engine).installed ->
            "Download ${name(config.output.engine)} in Settings → Voice output first."
        else -> null
    }
    fun output(config: VoiceConfiguration): VoiceOutput {
        startProblem(config)?.let { error(it) }
        config.output.validated()
        if (config.output.engine == VoiceOutputEngine.SYSTEM) {
            // Free the heavyweight engine when switching back to Android, off the UI thread.
            return object : VoiceOutput {
                private val voice = AndroidVoiceOutput(app, config.rate)
                private var prepareTask: Job? = null
                override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) {
                    prepareTask = scope.launch {
                        withContext(Dispatchers.IO) { runtime.unload(); pocketRuntime.unload() }
                        refresh(); voice.prepare(onReady, onError)
                    }
                }
                override fun speak(text: String, started: () -> Unit, complete: () -> Unit) = voice.speak(text, started, complete)
                override fun chime() = voice.chime()
                override fun stop() { prepareTask?.cancel(); voice.stop() }
                override fun close() { prepareTask?.cancel(); voice.close() }
            }
        }
        val voice = LocalVoiceOutput(modelRuntime(config.output.engine), config.output, config.rate,
            { thermals.severe }, cue = { readyChime() })
        return object : VoiceOutput by voice {
            override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) =
                voice.prepare({ refresh(); onReady(it) }, { refresh(); onError(it) })
        }
    }
    fun download(engine: VoiceOutputEngine = VoiceOutputEngine.QWEN) = perform {
        modelFiles(engine).download { n, total -> scope.launch { progress = n.toFloat() / total } }
        notice = "${name(engine)} is installed and verified. Select it and save to use it."
    }
    fun delete(engine: VoiceOutputEngine = VoiceOutputEngine.QWEN) = perform {
        withContext(Dispatchers.IO) { modelRuntime(engine).unload(); modelFiles(engine).delete() }
        notice = "${name(engine)} deleted. Select an installed voice or download it again."
    }
    fun unload() = perform {
        withContext(Dispatchers.IO) { runtime.unload(); pocketRuntime.unload() }
        notice = "Local output models unloaded."
    }
    fun load(engine: VoiceOutputEngine = VoiceOutputEngine.QWEN) = perform {
        check(!thermals.severe) { "Let the phone cool before loading a voice model." }
        val ms = withContext(Dispatchers.IO) { modelRuntime(engine).withModel { _, duration, _ -> duration } }
        notice = "${name(engine)} loaded on CPU in ${ms.toLong()} ms (includes verification)."
    }
    fun sample(config: VoiceConfiguration) {
        stopPreview()
        try {
            preview = output(config).also { voice ->
                busy = true; notice = "Loading preview…"
                voice.prepare({
                    refresh(); notice = "Generating preview…"
                    voice.speak("Hello. I’m Bellpull. Your voice stays on this device.",
                        { notice = "Playing preview…" }, { stopPreview(); notice = "Preview finished." })
                }, { stopPreview(); notice = it })
            }
        } catch (error: Exception) { busy = false; notice = error.message }
    }
    fun stopPreview() { preview?.close(); preview = null; busy = task?.isActive == true }
    fun cancel() { stopPreview(); task?.cancel(); runtime.invalidate(); pocketRuntime.invalidate() }
    private fun refresh() {
        installed = files.installed; partial = files.partial; loaded = runtime.loaded
        pocketInstalled = pocketFiles.installed; pocketPartial = pocketFiles.partial; pocketLoaded = pocketRuntime.loaded
    }
    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        stopPreview(); busy = true; progress = null; notice = null
        task = scope.launch {
            try { block() }
            catch (error: CancellationException) { notice = "Cancelled. Verified completed download files are kept for retry." }
            catch (error: Exception) { notice = error.message ?: "Voice output operation failed." }
            finally { refresh(); busy = false; progress = null; task = null }
        }
    }
}

/** Soft readiness cue, independent of the speech engine. */
internal fun readyChime() {
    val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 12) }.getOrNull() ?: return
    if (runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }.getOrDefault(false))
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 150)
    else tone.release()
}
