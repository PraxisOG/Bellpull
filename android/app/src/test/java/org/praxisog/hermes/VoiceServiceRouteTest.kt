package org.praxisog.hermes

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceServiceRouteTest {
    @Test fun headsetDisconnectOnlyLeavesVoiceRunningOnItsConfirmedSpeaker() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertTrue(VoiceService.stillOnSelectedSpeaker(speaker, 2, 2))
        assertFalse(VoiceService.stillOnSelectedSpeaker(speaker, 2, 16))
        assertFalse(VoiceService.stillOnSelectedSpeaker(speaker, 2, null))
        assertFalse(VoiceService.stillOnSelectedSpeaker(null, null, null))
        assertFalse(VoiceService.stillOnSelectedSpeaker(AudioDeviceInfo.TYPE_WIRED_HEADSET, 16, 16))
        assertFalse(VoiceService.stillOnSelectedSpeaker(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 20, 20))
    }
}
