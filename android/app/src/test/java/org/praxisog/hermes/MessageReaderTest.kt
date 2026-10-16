package org.praxisog.hermes

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageReaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val shadow = shadowOf(audio)
    private class Output : VoiceOutput {
        var complete: () -> Unit = {}
        var ready: (String) -> Unit = {}
        var failure: (String) -> Unit = {}
        var plays = 0
        var closed = false
        override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) {
            ready = onReady; failure = onError
        }
        override fun speak(text: String, started: () -> Unit, complete: () -> Unit) {
            plays++; this.complete = complete; started()
        }
        override fun stop() {}
        override fun close() { closed = true }
    }
    private val outputs = mutableListOf<Output>()
    private val usages = mutableListOf<Int>()
    private val reader = MessageReader(context) { usage ->
        usages += usage
        Output().also { outputs += it }
    }
    @After fun close() = reader.close()

    @Test fun readerRequestsMediaForTheEngineAndFocusAndReleasesAfterCompletion() {
        reader.read(1, "Read this answer.")
        outputs.single().ready("fixture")
        val request = shadow.lastAudioFocusRequest.audioFocusRequest
        assertEquals(listOf(AudioAttributes.USAGE_MEDIA), usages)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertEquals(1, outputs.single().plays)
        outputs.single().complete()
        assertNull(reader.readingMessageId)
        assertTrue(outputs.single().closed)
        assertSame(request, shadow.lastAbandonedAudioFocusRequest)
    }

    @Test fun deniedFocusDoesNotPlayAndKeepsAnActionableError() {
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        reader.read(1, "Answer")
        outputs.single().ready("fixture")
        assertEquals(0, outputs.single().plays)
        assertTrue(outputs.single().closed)
        assertNull(reader.readingMessageId)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Audio is unavailable"))
    }

    @Test fun staleFocusLossAndCompletionCannotStopANewMessage() {
        reader.read(1, "First answer")
        outputs[0].ready("fixture")
        val old = shadow.lastAudioFocusRequest
        reader.read(2, "Second answer")
        assertTrue(outputs[0].closed)
        assertSame(old.audioFocusRequest, shadow.lastAbandonedAudioFocusRequest)
        outputs[1].ready("fixture")
        old.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        outputs[0].complete()
        assertEquals(2, reader.readingMessageId)
        assertFalse(outputs[1].closed)
        val current = shadow.lastAudioFocusRequest
        current.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(outputs[1].closed)
        assertNull(reader.readingMessageId)
        current.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, outputs[1].plays)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("interrupted"))
    }

    @Test fun lateReadinessAfterStopCannotAcquireFocusOrPlay() {
        reader.read(1, "Answer")
        reader.stop()
        outputs.single().ready("fixture")
        assertNull(shadow.lastAudioFocusRequest)
        assertEquals(0, outputs.single().plays)
    }

    @Test fun outputFailureReleasesMediaFocus() {
        reader.read(1, "Answer")
        outputs.single().ready("fixture")
        val request = shadow.lastAudioFocusRequest.audioFocusRequest
        outputs.single().failure("Fixture output failed")
        assertNull(reader.readingMessageId)
        assertTrue(outputs.single().closed)
        assertSame(request, shadow.lastAbandonedAudioFocusRequest)
    }
}
