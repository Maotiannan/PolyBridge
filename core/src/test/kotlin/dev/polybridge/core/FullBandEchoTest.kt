package dev.polybridge.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class FullBandEchoTest {
    @Test fun longPlaybackMatchesAcrossWindowEdgesAndCaptureRatesWithoutHidingAnotherVoice() {
        val random = Random(2718)
        val reference = ShortArray(24_000 * 90) { (random.nextInt(12000) - 6000).toShort() }
        val echo = FullBandEcho(reference, 212.0)
        // Visit late audio first, cross cache boundaries in both directions, and change rates.
        val positions = listOf(2_100_001, 11_999, 12_001, 239_999, 240_001, 2_159_400, 3)
        for (rate in listOf(16000, 48000, 16000)) for (offset in positions) {
            val frame = ShortArray(rate / 50) { i ->
                (reference[offset + i * 24000 / rate] * .15 + 300 * sin(i * 2 * PI * 140 / rate)).toInt().toShort()
            }
            assertTrue("missed delayed echo offset=$offset rate=$rate",
                echo.confirms(frame, frame.size, rate, offset - 2, offset + 2, offset + 1))
            val otherVoice = ShortArray(frame.size) { i ->
                (frame[i] + 1800 * sin(i * 2 * PI * 191 / rate)).toInt().toShort()
            }
            assertFalse("erased independent voice offset=$offset rate=$rate",
                echo.confirms(otherVoice, otherVoice.size, rate, offset - 2, offset + 2, offset))
        }
    }
}
