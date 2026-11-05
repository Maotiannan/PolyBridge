package dev.polybridge.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class FullBandEchoTest {
    @Test fun boundedCorrelationAgreesWithUnprunedSearchAcrossLevelsPolaritiesAndShortFrames() {
        val random = Random(1409)
        val reference = ShortArray(3000) { (random.nextInt(12000) - 6000).toShort() }
        val echo = FullBandEcho(reference, 212.0)
        for (rate in listOf(16000, 48000)) for (n in listOf(32, 64, 137, 320)) {
            for (gain in listOf(.02, .15, -.15)) for (noise in listOf(0, 200, 800)) {
                val offset = 101 + random.nextInt(300)
                val frame = ShortArray(n * (rate / 16000)) { i ->
                    (reference[offset + i * 24000 / rate] * gain +
                        random.nextDouble() * noise * 2 - noise).toInt().toShort()
                }
                assertEquals("rate=$rate n=$n gain=$gain noise=$noise",
                    unpruned(reference, frame, rate, 212.0, 600),
                    echo.confirms(frame, frame.size, rate, 0, 600, 0))
            }
        }
    }

    /** Independent direct waveform reference: no cache, block pruning or production helpers. */
    private fun unpruned(reference: ShortArray, samples: ShortArray, rate: Int, background: Double, to: Int): Boolean {
        val stride = rate / 16000
        val input = DoubleArray(samples.size / stride) { i ->
            (0 until stride).sumOf { samples[i * stride + it].toDouble() } / stride
        }
        val high = DoubleArray(input.size) { i -> if (i < 2) 0.0 else input[i] - 2 * input[i - 1] + input[i - 2] }
        val power = high.sumOf { it * it }
        if (power < input.size * 16.0) return false
        for (offset in 0..to) for (linear in listOf(false, true)) {
            val raw = DoubleArray(input.size) { i ->
                val pos = offset + i * 3 / 2
                when {
                    linear && i % 2 != 0 -> (reference[pos] + reference[pos + 1]) / 2.0
                    linear || rate == 16000 -> reference[pos].toDouble()
                    else -> {
                        val left = if (i % 2 == 0) 2 else 1
                        (reference[pos] * left + reference[pos + 1] * (3 - left)) / 3.0
                    }
                }
            }
            var dot = 0.0; var energy = 0.0
            for (i in 2 until input.size) {
                val value = raw[i] - 2 * raw[i - 1] + raw[i - 2]
                dot += high[i] * value; energy += value * value
            }
            if (energy < input.size * 16.0 || dot * dot < .90 * power * energy) continue
            val gain = dot / energy
            if (kotlin.math.abs(gain) > 2.0) continue
            val residual = input.indices.sumOf { i -> val value = input[i] - gain * raw[i]; value * value }
            val room = background * 1.25 + 18
            if (residual <= room * room * input.size) return true
        }
        return false
    }

    @Test fun fullDelaySearchFindsBothResamplingVariantsWithoutMistakingIndependentNoiseForEcho() {
        val random = Random(8931)
        val reference = ShortArray(24_000) { (random.nextInt(12000) - 6000).toShort() }
        val echo = FullBandEcho(reference, 212.0)
        // Far from the hint: the exhaustive fallback must still examine the true delay.
        for (rate in listOf(16000, 48000)) for (linear in listOf(false, true)) {
            for (offset in listOf(103, 11_999, 12_002, 18_017)) {
                val frame = ShortArray(rate / 50) { i ->
                    val at = i * 16000 / rate
                    val position = offset + if (linear) at * 3 / 2 else i * 24000 / rate
                    val value = if (linear && at % 2 != 0)
                        (reference[position] + reference[position + 1]) / 2.0 else reference[position].toDouble()
                    (value * .15 + 250 * sin(i * 2 * PI * 140 / rate)).toInt().toShort()
                }
                assertTrue("lost full-search echo rate=$rate linear=$linear offset=$offset",
                    echo.confirms(frame, frame.size, rate, 0, 19_000, 50))
                val independent = ShortArray(frame.size) { (random.nextInt(12000) - 6000).toShort() }
                assertFalse(echo.confirms(independent, independent.size, rate, 0, 19_000, 50))
            }
        }
    }

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
