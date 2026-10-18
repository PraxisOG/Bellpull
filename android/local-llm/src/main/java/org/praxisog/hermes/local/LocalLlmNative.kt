package org.praxisog.hermes.local

/** All methods except cancel run on one owner thread. No network or Android service. */
object LocalLlmNative {
    init { System.loadLibrary("bellpull_llm") }
    external fun initialize()
    external fun clearCancel()
    external fun cancel()
    external fun load(path: String, gpu: Boolean): String
    external fun begin(messagesUtf8: ByteArray, maxTokens: Int)
    external fun next(): ByteArray?
    private external fun reportUtf8(): ByteArray
    fun report(): String = reportUtf8().toString(Charsets.UTF_8)
    external fun unload()
}
