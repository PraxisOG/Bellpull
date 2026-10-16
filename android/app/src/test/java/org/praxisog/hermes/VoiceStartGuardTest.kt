package org.praxisog.hermes

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceStartGuardTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var nativeCreations = 0
    private fun lab() = SpeechLab(context) { _, _ -> nativeCreations++; error("Must not load native code") }
    @Before fun reset() {
        context.getSharedPreferences("speech-experiments", Context.MODE_PRIVATE).edit().clear().commit()
        lab().let { lab -> lab.models.forEach(lab.files::delete) }
    }
    @After fun cleanup() { lab().let { lab -> lab.models.forEach(lab.files::delete) } }

    // Sparse placeholders exercise only the cheap preflight. Runtime checksum validation remains mandatory.
    private fun stage(lab: SpeechLab, complete: Boolean) {
        val folder = lab.files.directory(lab.selected).also { it.mkdirs() }
        lab.selected.files.forEach { file -> RandomAccessFile(File(folder, file.name), "rw").use { it.setLength(file.bytes) } }
        if (complete) File(folder, ".complete").writeText(lab.selected.checkpoint)
    }

    @Test fun everyMissingModelIsBlockedBeforeConstructingARecognizer() {
        lab().models.forEach { model ->
            SpeechExperimentSettings(context).select(model.id)
            val lab = lab()
            assertEquals(model.id, lab.selectedId)
            assertTrue(lab.voiceStartProblem()!!.contains("Download ${model.label}"))
            val error = assertThrows(IllegalStateException::class.java) { lab.voiceTranscriber() }
            assertEquals(lab.voiceStartProblem(), error.message)
        }
        assertEquals(0, nativeCreations)
    }

    @Test fun incompleteAndDeletedFilesBlockEvenWhenTheInstalledSnapshotIsStale() {
        val initial = lab()
        stage(initial, complete = false)
        assertNotNull(initial.voiceStartProblem())
        stage(initial, complete = true)
        val lab = lab()
        assertTrue(lab.selectedId in lab.installed)
        assertNull(lab.voiceStartProblem())
        File(lab.files.directory(lab.selected), lab.selected.files.first().name).delete()
        assertTrue(lab.selectedId in lab.installed) // Reproduce stale UI state.
        assertNotNull(lab.voiceStartProblem())
        assertThrows(IllegalStateException::class.java) { lab.voiceTranscriber() }
        stage(lab, complete = true)
        File(lab.files.directory(lab.selected), ".complete").delete()
        assertNotNull(lab.voiceStartProblem())
        assertThrows(IllegalStateException::class.java) { lab.voiceTranscriber() }
        assertEquals(0, nativeCreations)
    }

    @Test fun installedButUnloadedModelCanStartAndServerModeDoesNotCheckLocalWeights() {
        val lab = lab()
        stage(lab, complete = true)
        assertNull(lab.runtime.loadedModel)
        assertNull(VoiceConfiguration().startProblem(lab::voiceStartProblem))
        lab.voiceTranscriber().close()
        assertEquals(0, nativeCreations) // Loading is deferred to the transcription worker.
        lab.files.delete(lab.selected)
        assertNull(VoiceConfiguration(transcribeViaServer = true).startProblem {
            fail("Server mode must not inspect local models"); null
        })
        assertNotNull(VoiceConfiguration(recognition = VoiceRecognition.PRIVATE_SERVER).startProblem {
            fail("Private mode must not inspect local models"); null
        })
    }
}
