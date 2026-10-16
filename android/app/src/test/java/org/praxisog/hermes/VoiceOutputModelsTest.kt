package org.praxisog.hermes

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class VoiceOutputModelsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val data = listOf("talker".toByteArray(), "codec".toByteArray())
    private val specs = data.mapIndexed { i, bytes -> VoiceModelFile("file$i.gguf", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }
    @Test fun shippedModelManifestsConstructBeforeAnyModelIsInstalled() {
        val root = temporary.newFolder()
        for (model in listOf(qwenOutputModel, PocketModel.model, MossModel.model)) {
            val files = VoiceOutputFiles(root, model.files, model)
            assertEquals(File(root, "${model.id}-${model.revision}"), files.directory)
            assertFalse(files.installed)
            assertFalse(files.partial)
            assertThrows(IllegalStateException::class.java) { files.verify() }
        }
    }
    @Test fun modelIdsCannotEscapeTheirOwnedFolder() {
        for (id in listOf("../qwen", "/qwen", ".hidden", "qwen/model", "qwen\\model", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                VoiceOutputFiles(temporary.newFolder(), model = qwenOutputModel.copy(id = id))
            }
        }
    }
    @Test fun verifiedInstallRetryAndDeleteOnlyTouchOwnedFolders() = runBlocking {
        val root = temporary.newFolder(); val unrelated = File(root, "notes.txt").apply { writeText("keep me") }
        var opens = 0
        val files = VoiceOutputFiles(root, specs) { file -> opens++; ByteArrayInputStream(data[specs.indexOf(file)]) }
        assertFalse(files.installed)
        files.download { _, _ -> }; assertTrue(files.installed); files.verify()
        files.download { _, _ -> }; assertEquals(2, opens)
        files.delete(); assertFalse(files.installed); assertFalse(files.partial); assertEquals("keep me", unrelated.readText())
    }
    @Test fun interruptedSecondFileRetainsFirstVerifiedFileForRetry() = runBlocking {
        val root = temporary.newFolder(); var fail = true; var firstOpened = 0
        val files = VoiceOutputFiles(root, specs) { file ->
            if (file == specs[0]) firstOpened++
            if (file == specs[1] && fail) throw java.io.IOException("Disconnected")
            ByteArrayInputStream(data[specs.indexOf(file)])
        }
        try { files.download { _, _ -> }; fail("Should fail") } catch (_: java.io.IOException) { }
        assertFalse(files.installed); assertTrue(files.partial)
        fail = false; files.download { _, _ -> }
        assertTrue(files.installed); assertEquals(1, firstOpened)
    }
    @Test fun wrongHashAndOversizedDownloadNeverBecomeInstalled() = runBlocking {
        for (bad in listOf("xxxxxx".toByteArray(), "toolongtoo".toByteArray())) {
            val files = VoiceOutputFiles(temporary.newFolder(), specs) { ByteArrayInputStream(bad) }
            try { files.download { _, _ -> }; fail("Accepted bad data") } catch (_: IllegalStateException) { }
            assertFalse(files.installed); assertFalse(File(files.directory, ".complete").exists())
        }
    }
    @Test fun corruptionAfterInstallationIsRejectedBeforeNativeLoading() = runBlocking {
        val files = VoiceOutputFiles(temporary.newFolder(), specs) { ByteArrayInputStream(data[specs.indexOf(it)]) }
        files.download { _, _ -> }
        File(files.directory, specs[0].name).writeText("xxxxxx")
        assertTrue(files.installed) // Cheap availability check; loading must perform full verification.
        assertThrows(IllegalStateException::class.java) { files.verify() }; Unit
    }
    @Test fun cancelledDownloadDoesNotPublishACompletionMarker() = runBlocking {
        val files = VoiceOutputFiles(temporary.newFolder(), specs) { ByteArrayInputStream(data[specs.indexOf(it)]) }
        val job = launch { files.download { _, _ -> throw CancellationException() } }
        job.join(); assertTrue(job.isCancelled); assertFalse(files.installed); assertTrue(files.partial)
    }
    @Test fun traversalAndUnsupportedControlsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { VoiceOutputFiles(temporary.newFolder(), listOf(specs[0].copy(name = "../file.gguf"), specs[1])) }
        assertThrows(IllegalArgumentException::class.java) { VoiceOutputConfiguration(speaker = "invented").validated() }
        assertThrows(IllegalArgumentException::class.java) { VoiceOutputConfiguration(temperature = Float.NaN).validated() }
        assertThrows(IllegalArgumentException::class.java) { VoiceOutputConfiguration(seed = -2).validated() }
        assertEquals(859852832L, QwenModel.bytes)
    }
}
