package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImportedAudioTest {
    @Test fun normalizesStereoAndPreservesDurationAcrossSmallBuffers() {
        val sound = ImportedAudio(maxSeconds = 1)
        repeat(30) {
            val b = ByteBuffer.allocate(441 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(441) { b.putShort(1000); b.putShort(3000) }; b.flip()
            sound.add(b, 44100, 2, false)
        }
        val data = sound.wave()
        assertEquals(44 + 4800 * 2, data.size)
        val wave = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, wave.getInt(24))
        assertEquals(2000, wave.getShort(44).toInt())
    }
    @Test fun rejectsOverlongOrEmptyAudio() {
        val empty = ImportedAudio(1)
        assertThrows(IllegalArgumentException::class.java) { empty.wave() }
        val long = ByteBuffer.allocate(16001 * 2)
        assertThrows(IllegalArgumentException::class.java) { empty.add(long, 16000, 1, false) }
    }
}
