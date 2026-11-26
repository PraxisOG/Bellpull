package org.praxisog.hermes

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoicePcmDrainDeviceTest {
    @Test fun phraseShorterThanTheStartThresholdFinishes() = runBlocking {
        val player = AndroidVoicePcmPlayer(rate = 1.05f, volume = 0f)
        try {
            player.write(FloatArray(1_920))
            withTimeout(8_000) { player.drain() }
        } finally {
            player.close()
        }
    }

    @Test fun shortFinalChunkFinishesAfterAnUnderrun() = runBlocking {
        val player = AndroidVoicePcmPlayer(rate = 1.05f, volume = 0f)
        try {
            player.write(FloatArray(24_000))
            delay(1_500)
            player.write(FloatArray(1_920))
            withTimeout(8_000) { player.drain() }
        } finally {
            player.close()
        }
    }
}
