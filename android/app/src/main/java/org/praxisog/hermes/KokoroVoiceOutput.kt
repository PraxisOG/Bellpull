package org.praxisog.hermes

import java.io.File
import java.net.URI

internal object KokoroModel {
    const val revision = "1939ad2a8e416c0acfeecc08a694d14ef25f2231"
    const val g2pRevision = "4b3a605d2f605318ab4f762ab9855c44106528b7"
    val voices = listOf("af_heart", "af_bella", "am_michael", "am_fenrir")
    val model = OutputModel("kokoro-82m-fp32", "Kokoro 82M", revision,
        "onnx-community/Kokoro-82M-v1.0-ONNX", listOf(
            VoiceModelFile("model.onnx", 325532232, "8fbea51ea711f2af382e88c833d9e288c6dc82ce5e98421ea61c058ce21a34cb", "onnx/model.onnx"),
            VoiceModelFile("af_heart.bin", 522240, "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b", "voices/af_heart.bin"),
            VoiceModelFile("af_bella.bin", 522240, "f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b", "voices/af_bella.bin"),
            VoiceModelFile("am_michael.bin", 522240, "1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1", "voices/am_michael.bin"),
            VoiceModelFile("am_fenrir.bin", 522240, "c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43", "voices/am_fenrir.bin"),
            VoiceModelFile("misaki-en_us-g2p.onnx", 3438244, "757759c3e3ccb0bf59c379b1671c9834a27543ceaefd6846850823633b9665a1"),
            VoiceModelFile("misaki-en_us-g2p.json", 5724857, "8a8e3350713b0a00102425a5d5bd6b58b34854c74a3e8d837589f55212c51658")))
    fun uri(spec: VoiceModelFile): URI {
        require(spec in model.files)
        return if (spec.name.startsWith("misaki-"))
            URI("https://huggingface.co/nekwo/misaki-en-us-g2p-onnx/resolve/$g2pRevision/${spec.name}")
        else URI("https://huggingface.co/${model.repository}/resolve/$revision/${spec.remoteName}")
    }
}

internal object KokoroNative {
    init { System.loadLibrary("bellpull_pocket") }
    external fun load(model: String, g2p: String, threads: Int): Long
    external fun free(handle: Long)
    external fun predict(handle: Long, ids: LongArray, sink: LocalTtsSink): IntArray
    external fun synthesize(handle: Long, ids: LongArray, voice: String, sink: LocalTtsSink): Int
}

internal class NativeKokoroEngine(private val files: VoiceOutputFiles, threads: Int = 4, modelFile: File = File(files.directory, "model.onnx")) : LocalTtsEngine {
    private val frontend = KokoroPhonemizer(File(files.directory, "misaki-en_us-g2p.json").readText())
    private var handle = KokoroNative.load(modelFile.path,
        File(files.directory, "misaki-en_us-g2p.onnx").path, threads).also { check(it != 0L) }
    override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
        check(handle != 0L); config.validated()
        val chunks = frontend.chunks(text, cancelled = sink::isCancelled) { ids ->
            KokoroNative.predict(handle, ids, sink)
        }
        for (ids in chunks) {
            if (sink.isCancelled()) return 1
            val result = KokoroNative.synthesize(handle, ids, File(files.directory, "${config.kokoroVoice}.bin").path, sink)
            if (result != 0) return result
        }
        return if (sink.isCancelled()) 1 else 0
    }
    override fun close() { if (handle != 0L) { KokoroNative.free(handle); handle = 0 } }
}
