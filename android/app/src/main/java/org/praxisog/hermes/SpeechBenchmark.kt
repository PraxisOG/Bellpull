package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/** One native model in memory. Switching and inference use the same exclusive lock. */
internal class SpeechModelRuntime(private val verify: (SpeechModel) -> Unit,
    private val create: (SpeechModel) -> LocalSpeechEngine) : AutoCloseable {
    private val lock = ReentrantLock()
    private var current: Pair<String, LocalSpeechEngine>? = null
    @Volatile var loadedModel: String? = null
        private set
    fun <T> withModel(model: SpeechModel, block: (LocalSpeechEngine, Double) -> T): T = lock.withLock {
        var loadMs = 0.0
        if (current?.first != model.id) {
            val old = current
            current = null
            loadedModel = null
            old?.second?.close()
            val start = System.nanoTime()
            verify(model)
            current = model.id to create(model)
            loadedModel = model.id
            loadMs = (System.nanoTime() - start) / 1e6
        }
        try { block(current!!.second, loadMs) }
        catch (failure: Throwable) {
            // A cancelled or failed stream must never leak into the next utterance.
            try { current?.second?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            current = null
            loadedModel = null
            throw failure
        }
    }
    override fun close() = lock.withLock {
        val old = current
        current = null
        loadedModel = null
        old?.second?.close()
        Unit
    }
}

internal data class SpeechFixture(val id: String, val reference: String, val audio: SpeechAudio,
    val lastWordEndMs: Double?, val singleTurn: Boolean = true)

internal class SpeechThermals(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private var headroomAt = 0L
    private var headroom: Float? = null
    val severe get() = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    fun snapshot(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 30 && (headroomAt == 0L || now - headroomAt >= 10_000)) {
            headroom = runCatching { power.getThermalHeadroom(0) }.getOrNull()?.takeIf { it.isFinite() }
            headroomAt = now
        }
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return "status=${power.currentThermalStatus};headroom=${headroom ?: "unavailable"};" +
            "headroom_age_ms=${now - headroomAt};battery_c=${temperature ?: "unavailable"};plugged=${charging ?: "unknown"}"
    }
}

/** Small direct-input benchmark. Timing references must be annotated, never inferred from the model. */
internal class SpeechBenchmarker(private val runtime: SpeechModelRuntime, private val thermals: SpeechThermals) {
    suspend fun measure(model: SpeechModel, fixture: SpeechFixture, feed: SpeechFeed, repetition: Int): SpeechRunMeasurement = coroutineScope {
        check(!thermals.severe) { "Phone reached severe thermal status. Let it cool before continuing." }
        val thermalStart = thermals.snapshot()
        val peakPss = AtomicInteger()
        val peakRss = AtomicInteger()
        fun memory() {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            peakPss.accumulateAndGet(info.totalPss, ::maxOf)
            val rss = runCatching { File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: 0
            } }.getOrDefault(0)
            peakRss.accumulateAndGet(rss, ::maxOf)
        }
        memory()
        val benchmarkJob = currentCoroutineContext()[Job]!!
        val memoryMonitor = launch(Dispatchers.IO) { while (isActive) {
            memory()
            if (thermals.severe) {
                benchmarkJob.cancel(CancellationException("Phone reached severe thermal status."))
                break
            }
            delay(250)
        } }
        try {
            withContext(Dispatchers.IO) {
                // Blocking native calls and the lock stay on the worker thread, never Compose/main.
                runtime.withModel(model) { engine, loadMs ->
                    val started = System.nanoTime()
                    var firstPartial: Double? = null
                    var lastCompleted: Double? = null
                    var premature = 0
                    var forcing = false
                    var forcedLast = false
                    var deliveredMs = 0.0
                    val task = coroutineContext[Job]!!
                    task.ensureActive()
                    check(!thermals.severe) { "Phone reached severe thermal status. Benchmark stopped after model loading." }
                    val text = if (feed == SpeechFeed.THROUGHPUT) engine.transcribe(fixture.audio) else {
                        engine.start { update ->
                            if (update.text.isNotBlank()) {
                                val at = (System.nanoTime() - started) / 1e6
                                if (!update.completedLine && firstPartial == null) firstPartial = at
                                if (update.completedLine) {
                                    forcedLast = forcing
                                    if (fixture.lastWordEndMs != null && update.audioEndMs != null &&
                                        update.audioEndMs >= fixture.lastWordEndMs - 150 && deliveredMs >= fixture.lastWordEndMs) {
                                        lastCompleted = at
                                    }
                                    if (fixture.singleTurn && fixture.lastWordEndMs != null && deliveredMs < fixture.lastWordEndMs - 150) premature++
                                }
                            }
                        }
                        val frameSize = 1600 // 100 ms; arrival paced to the end of each chunk.
                        for (offset in fixture.audio.samples.indices step frameSize) {
                            task.ensureActive()
                            check(!thermals.severe) { "Phone reached severe thermal status. Benchmark stopped." }
                            val end = minOf(offset + frameSize, fixture.audio.samples.size)
                            val target = started + end * 1_000_000_000L / fixture.audio.sampleRate
                            while (System.nanoTime() < target) {
                                task.ensureActive()
                                Thread.sleep(minOf(10L, ((target - System.nanoTime()) / 1_000_000).coerceAtLeast(1)))
                            }
                            deliveredMs = end * 1000.0 / fixture.audio.sampleRate
                            engine.accept(fixture.audio.samples.copyOfRange(offset, end))
                        }
                        forcing = true
                        engine.finish()
                    }
                    task.ensureActive()
                    val elapsed = (System.nanoTime() - started) / 1e6
                    val errors = wordErrors(fixture.reference, text)
                    memory()
                    SpeechRunMeasurement(model.id, model.checkpoint, model.quantization, engine.runtimeVersion,
                        engine.actualBackend, fixture.id, feed.name, repetition, loadMs, fixture.audio.seconds, elapsed,
                        firstPartial, fixture.lastWordEndMs?.let { end -> lastCompleted?.minus(end) },
                        forcedLast, premature, errors.edits, errors.referenceWords, peakPss.get(), peakRss.get(),
                        thermalStart, thermals.snapshot(), text, fixture.audio.sourceSha256,
                        engine.nativeInitializationMs.takeIf { loadMs > 0 })
                }
            }
        } finally { memoryMonitor.cancelAndJoin() }
    }
}
