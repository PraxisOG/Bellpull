package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class KokoroPhonemizerTest {
    private val meta = """{"gold":{"hello":"hɛlo","world":"wɜɹld","cat":"kæt","A":"A","B":"bi"},"silver":{},"graphemes":"____abcdefghijklmnopqrstuvwxyz","phonemes":"____hɛlo","bos_id":1,"eos_id":2,"unknown_id":3,"kokoro_vocab":{" ":16,"h":50,"ɛ":86,"l":54,"o":31,"w":65,"ɜ":87,"ɹ":123,"d":46,".":4,"k":53,"æ":69,"t":62,"s":61,"A":24,"b":44,"i":51}}"""
    @Test fun numbersCurrencyAndSymbolsAreSpokenRatherThanDropped() {
        assertEquals("twelve dollars and fifty cents and ten percent ", KokoroPhonemizer.normalize("$12.50 and 10%"))
        assertEquals("three point one four", KokoroPhonemizer.normalize("3.14"))
        assertEquals("one million", KokoroPhonemizer.normalize("1,000,000"))
        assertEquals("cafe", KokoroPhonemizer.normalize("café"))
        assertThrows(IllegalArgumentException::class.java) { KokoroPhonemizer.normalize("你好") }
    }
    @Test fun unknownWordsUseNeuralFallbackAndLongPassagesAreNeverTruncated() {
        val g2p = KokoroPhonemizer(meta); var fallback = 0
        assertEquals("hɛlo", g2p.word("unlisted", null) { fallback++; intArrayOf(1, 4, 5, 6, 7, 2) })
        assertEquals("hɛlo", g2p.word("unlisted", null) { error("Must reuse bounded cache") })
        assertEquals(1, fallback)
        val short = g2p.chunks("hello world.") { error("Dictionary") }.toList()
        val long = g2p.chunks("hello world. ".repeat(100)) { error("Dictionary") }.toList()
        assertEquals(100, long.size)
        assertTrue(long.all { it.contentEquals(short.single()) && it.size <= 162 })
        assertThrows(CancellationException::class.java) { g2p.chunks("hello", { true }) { intArrayOf() }.toList() }
    }
    @Test fun kokoroSelectionAndPinnedSourcesAreIndependentOfQwenControls() {
        assertEquals(VoiceOutputEngine.KOKORO, VoiceOutputEngine.decode("kokoro"))
        assertEquals("am_michael", VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO, kokoroVoice = "am_michael").validated().kokoroVoice)
        assertThrows(IllegalArgumentException::class.java) { VoiceOutputConfiguration(kokoroVoice = "../../bad").validated() }
        for (file in KokoroModel.model.files) {
            val uri = voiceModelUri(file, KokoroModel.model)
            assertEquals("https", uri.scheme)
            assertTrue(uri.path.contains(if (file.name.startsWith("misaki")) KokoroModel.g2pRevision else KokoroModel.revision))
        }
    }
}
