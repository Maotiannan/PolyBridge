package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackWaveTest {
    @Test fun normalizesTheStreamingLengthSentBySpeechSynthesisBeforeLocalPlayback() {
        val wave = MonoWave(24000).apply { append(shortArrayOf(320, -520), 2) }.bytes()
        val streaming = wave.clone()
        ByteBuffer.wrap(streaming).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, 0x7fffffbf); putInt(40, 0x7fffff9b)
        }
        assertArrayEquals(wave, playableWave(streaming))
        assertArrayEquals(wave, playableWave(wave))
        assertThrows(IllegalArgumentException::class.java) { playableWave(byteArrayOf(1, 2, 3)) }
    }
}
