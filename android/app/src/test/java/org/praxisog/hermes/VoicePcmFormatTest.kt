package org.praxisog.hermes

import android.media.AudioAttributes
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePcmFormatTest {
    @Test fun actualPlaybackTracksKeepEngineSampleRatesSeparateFromAudioUsage() {
        for ((engine, expectedRate) in listOf(VoiceOutputEngine.QWEN to 24000,
            VoiceOutputEngine.MOSS to 48000, VoiceOutputEngine.SERVER_MOSS to 48000)) {
            for (usage in listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_VOICE_COMMUNICATION)) {
                voicePcmPlayer(engine, 1f, 1f, usage).use { player ->
                    val track = ReflectionHelpers.getField<AudioTrack>(player, "track")
                    assertEquals("$engine sample rate", expectedRate, track.sampleRate)
                    assertEquals("$engine audio route", usage, track.audioAttributes.usage)
                    assertEquals(1, track.channelCount)
                }
            }
        }
    }
}
