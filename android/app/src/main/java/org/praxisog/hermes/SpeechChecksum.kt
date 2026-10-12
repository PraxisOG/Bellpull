package org.praxisog.hermes

import java.util.zip.Checksum

/** RFC 3720 Castagnoli CRC, for Android 29–33 which have no java.util.zip.CRC32C. */
internal class SpeechCrc32c : Checksum {
    private var crc = -1
    override fun reset() { crc = -1 }
    override fun getValue() = (crc.inv().toLong() and 0xffffffffL)
    override fun update(value: Int) { crc = table[(crc xor value) and 255] xor (crc ushr 8) }
    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        for (at in offset until offset + length) update(bytes[at].toInt())
    }
    companion object {
        private val table = IntArray(256) { value ->
            var crc = value
            repeat(8) { crc = (crc ushr 1) xor if (crc and 1 != 0) 0x82f63b78.toInt() else 0 }
            crc
        }
    }
}
