package org.praxisog.hermes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32C

class SpeechModelsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = mapOf("encoder.ort" to ByteArray(500) { it.toByte() }, "tokenizer.bin" to "tokens".toByteArray())
    private val model = SpeechModel("moonshine-tiny", "Tiny", 2, "quantized_26_08_21", "INT8", bytes.map { (name, data) ->
        val crc = CRC32C().also { it.update(data) }
        SpeechModelFile(name, "https://fixture.invalid/$name", data.size.toLong(),
            Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()))
    })
    @Test fun crcMatchesRfcVectorAndNativeImplementationAcrossChunks() {
        val fallback = SpeechCrc32c()
        fallback.update("123456789".toByteArray())
        assertEquals(0xe3069283L, fallback.value)
        val data = ByteArray(32001) { (it * 117).toByte() }
        fallback.reset(); fallback.update(data, 0, 300); fallback.update(data, 300, data.size - 300)
        assertEquals(CRC32C().also { it.update(data) }.value, fallback.value)
    }
    @Test fun interruptedDownloadRetainsVerifiedFilesAndRetriesOnlyMissingFile() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val calls = mutableListOf<String>()
        var fail = true
        val files = SpeechModelFiles(root) { file ->
            calls += file.name
            if (file.name == "tokenizer.bin" && fail) throw IOException("offline")
            ByteArrayInputStream(bytes.getValue(file.name))
        }
        try { files.download(model) { _, _ -> }; fail("Must fail") } catch (_: IOException) { }
        assertFalse(files.installed(model))
        fail = false
        files.download(model) { _, _ -> }
        assertEquals(listOf("encoder.ort", "tokenizer.bin", "tokenizer.bin"), calls)
        files.verify(model)
        assertTrue(files.installed(model))
    }
    @Test fun corruptDownloadCannotPublishAndDeleteKeepsSiblingModelsAndUserFiles() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val userFile = temporary.newFile("notes.txt").also { it.writeText("keep") }
        val files = SpeechModelFiles(root) { ByteArrayInputStream(ByteArray(it.bytes.toInt()) { 42 }) }
        try { files.download(model) { _, _ -> }; fail("Must reject corrupt bytes") } catch (_: IllegalStateException) { }
        assertFalse(files.installed(model))
        assertFalse(files.directory(model).exists())
        val sibling = files.directory(model.copy(id = "moonshine-small")).also { it.mkdirs() }
        files.delete(model)
        assertTrue(sibling.exists()); assertEquals("keep", userFile.readText())
        assertThrows(IllegalArgumentException::class.java) { files.delete(model.copy(id = "../notes")) }
        assertTrue(sibling.exists())
    }
    @Test fun installedModelIsReverifiedAndSameLengthCorruptionIsRejected() = runBlocking<Unit> {
        val files = SpeechModelFiles(temporary.newFolder()) { ByteArrayInputStream(bytes.getValue(it.name)) }
        files.download(model) { _, _ -> }; files.verify(model)
        java.io.File(files.directory(model), "tokenizer.bin").writeBytes("broken".toByteArray())
        assertTrue(files.installed(model)) // Length is only the inexpensive UI check.
        assertThrows(IllegalStateException::class.java) { files.verify(model) }
    }
    @Test fun cancelledPublicationCanResumeWithoutRedownloadingVerifiedParts() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val files = SpeechModelFiles(temporary.newFolder()) { calls += it.name; ByteArrayInputStream(bytes.getValue(it.name)) }
        var updates = 0
        try {
            files.download(model) { _, _ -> if (++updates == 2) throw kotlinx.coroutines.CancellationException() }
            fail("Must cancel")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(files.installed(model))
        files.download(model) { _, _ -> }
        files.verify(model)
        assertEquals(listOf("encoder.ort", "tokenizer.bin"), calls)
    }
}
