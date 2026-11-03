package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackWaveTest {
    @Test fun aLongReceivedTranslationKeepsItsEntireAudio() {
        val pcm = ByteArray(31 * 24000 * 2)
        pcm[pcm.lastIndex] = 57
        val wave = waveFromPcm(24000, pcm)
        assertEquals(44 + pcm.size, wave.size)
        assertEquals(pcm.size, ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertEquals(57, wave.last().toInt())
    }

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
