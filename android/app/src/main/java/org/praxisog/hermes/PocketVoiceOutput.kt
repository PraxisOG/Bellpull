package org.praxisog.hermes

internal object PocketNative {
    init { System.loadLibrary("bellpull_pocket") }
    external fun load(directory: String, threads: Int): Long
    external fun free(handle: Long)
    external fun synthesize(handle: Long, text: ByteArray, steps: Int, temperature: Float,
        chunkFrames: Int, seed: Int, sink: LocalTtsSink): Int
}

/** Same fenced PCM/cancellation interface as Qwen, with a CPU-only Pocket runtime. */
internal class NativePocketEngine(files: VoiceOutputFiles) : LocalTtsEngine {
    private var handle = PocketNative.load(files.directory.path, 4).also { check(it != 0L) }
    override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
        check(handle != 0L)
        config.validated()
        require(config.engine == VoiceOutputEngine.POCKET)
        return PocketNative.synthesize(handle, text.toByteArray(Charsets.UTF_8), config.pocketSteps,
            config.pocketTemperature, config.pocketChunkFrames, config.seed.toInt(), sink)
    }
    override fun close() { if (handle != 0L) { PocketNative.free(handle); handle = 0 } }
}
