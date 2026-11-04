package dev.polybridge.eval

import org.junit.Assert.*
import org.junit.Test

class EvaluationAudioTest {
    @Test fun pauseKeepsBothClipsAndInsertsExactDigitalSilence() {
        val joined = EvaluationAudio.withPause(byteArrayOf(1, 2), byteArrayOf(3, 4), 15000)
        assertEquals(480004, joined.size)
        assertArrayEquals(byteArrayOf(1, 2), joined.copyOfRange(0, 2))
        assertTrue(joined.copyOfRange(2, joined.size - 2).all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(3, 4), joined.takeLast(2).toByteArray())
    }
    @Test fun invalidPcmOrUnboundedPauseIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EvaluationAudio.withPause(byteArrayOf(1), byteArrayOf(1, 2), 1000) }
        assertThrows(IllegalArgumentException::class.java) { EvaluationAudio.withPause(byteArrayOf(1, 2), byteArrayOf(1, 2), 60000) }
    }
}
