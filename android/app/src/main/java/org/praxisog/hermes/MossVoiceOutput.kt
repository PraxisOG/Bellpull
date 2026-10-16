package org.praxisog.hermes

import java.io.File
import java.net.URI

/** Hashes/sizes copied from docs/MOSS-NANO-MANIFEST.txt; both upstreams resolve main.
 * The local revision identifies this verified manifest, not mutable upstream contents.
 * A changed upstream file fails verification rather than silently replacing weights.
 */
internal object MossModel {
    const val sampleRate = 48000
    const val voice = "Junhao"
    const val ttsRepository = "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX"
    const val codecRepository = "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX"
    val model = OutputModel("moss-nano-onnx", "MOSS-TTS-Nano", "manifest-2026-10-16", ttsRepository, listOf(
        VoiceModelFile("browser_poc_manifest.json", 503354, "097d80e993dc29f0bae427590b4f77084a161cb578b50d82c29f455d5faa9eee"),
        VoiceModelFile("moss_tts_decode_step.onnx", 291483, "698cbc2fc1c2feca16e5895614ed52bbb32ded10f236c076f477b2e69abf32d8"),
        VoiceModelFile("moss_tts_global_shared.data", 440813568, "bce8312c3df6a44545302cae229b61054fe0672e0b252ba59cba47adeed831dc"),
        VoiceModelFile("moss_tts_local_cached_step.onnx", 53685, "aa9035fefc1c138a951a8bcfc0374fb03a25f1ece67f7f7f53bce349b84a1dd5"),
        VoiceModelFile("moss_tts_local_decoder.onnx", 49231, "51aa754301b38550a5f9adda0ad93bd3dc95819afb511e6dcabf4a90b345a454"),
        VoiceModelFile("moss_tts_local_fixed_sampled_frame.onnx", 471262, "40cdb00efc171c450cf91468e01429caa41b0252222cd308e978f58fe354afa8"),
        VoiceModelFile("moss_tts_local_shared.data", 229678080, "bae7782032c0fb12490ab42afe009f87ae6c75a0f0596fc7b5c08e4d5ee93916"),
        VoiceModelFile("moss_tts_prefill.onnx", 283305, "d56126dcd0574c2f15d98fc6b35eda68d0386b5bd9c5e38e28548d6f2ea8f3db"),
        VoiceModelFile("tokenizer.model", 470897, "c353ee1479b536bf414c1b247f5542b6607fb8ae91320e5af1781fee200fddff"),
        VoiceModelFile("tts_browser_onnx_meta.json", 4487, "3edf25232dcd0af3d061c837e9a968a39e2f8592e06777d740503c4f2244f95c"),
        VoiceModelFile("codec_browser_onnx_meta.json", 17036, "3e291c883bb7d11ff2fe8e964e3e495519760358859f35c951254c7741592731"),
        VoiceModelFile("moss_audio_tokenizer_decode_full.onnx", 681902, "0fbbafe3fd4afa2a019af5c5ced204af6e2d1db044fa40f021525d2aee95b4ac"),
        VoiceModelFile("moss_audio_tokenizer_decode_shared.data", 44198912, "e69d52e0f4e84ca27850557ee54face46632d3a5a16c89bd246c7c408466dcad"),
        VoiceModelFile("moss_audio_tokenizer_decode_step.onnx", 351400, "9527c86a29e1837edec1f74db57d5eeaadb3a715af3382703566460afed25855"),
        VoiceModelFile("moss_audio_tokenizer_encode.data", 44507136, "aa751265b2bab2887eac224484546b194875aa7494b607115439b3dc6b228a2c"),
        VoiceModelFile("moss_audio_tokenizer_encode.onnx", 815775, "eadea4a645abdcf98714c7aead122ee2ce7da6e080f9f80b977cd1ca8e19473a"),
    ))
    fun uri(file: VoiceModelFile): URI {
        require(file in model.files)
        val repository = if (file.name.startsWith("moss_audio_tokenizer_") ||
            file.name == "codec_browser_onnx_meta.json") codecRepository else ttsRepository
        return URI("https://hf.co/$repository/resolve/main/${file.remoteName}")
    }
}

internal object MossNative {
    init { System.loadLibrary("bellpull_moss") }
    external fun tokenize(modelPath: String, text: String): IntArray
}

/** The lab serializes synthesis/unload and verifies all sixteen assets before construction. */
internal class NativeMossEngine(files: VoiceOutputFiles) : LocalTtsEngine {
    private val tokenizer = File(files.directory, "tokenizer.model")
    private val engine = MossOnnxEngine(files.directory)
    override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
        config.validated()
        require(config.engine == VoiceOutputEngine.MOSS)
        if (sink.isCancelled()) return -5
        val tokens = MossNative.tokenize(tokenizer.path, text)
        if (sink.isCancelled()) return -5
        return engine.synthesize(tokens, sink, if (config.seed == -1L) java.util.Random().nextLong() else config.seed)
    }
    override fun close() = engine.close()
}
