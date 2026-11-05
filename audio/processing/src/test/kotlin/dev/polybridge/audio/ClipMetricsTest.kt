package dev.polybridge.audio

import dev.polybridge.evaluation.noise.*
import org.junit.Assert.*
import org.junit.Test

class ClipMetricsTest {
    @Test fun missingOpeningAndEndingAreNotHiddenByRetainingTheMiddle() {
        val clean = ShortArray(16000) { if (it % 2 == 0) 1000 else -1000 }
        val output = clean.copyOf().apply { fill(0, 0, 4000); fill(0, 12000, size) }
        val score = scoreTurns(clean, output, listOf(VoiceSpan(0, 16000, "fr"))).single()
        assertEquals(.5, score.retained, .000001)
        assertEquals(0.0, score.openingRetained, .000001)
        assertEquals(0.0, score.endingRetained, .000001)
        assertTrue(score.gainDb < -2)
    }
    @Test fun quietSpeechIsMeasuredRelativeToItsOwnLevelAndZeroReferenceIsRejected() {
        val clean = ShortArray(16000) { if (it in 4000 until 12000) if (it % 2 == 0) 30 else -30 else 0 }
        val score = scoreTurns(clean, clean, listOf(VoiceSpan(0, 16000, "zh"))).single()
        assertEquals(1.0, score.retained, .000001)
        assertEquals(1.0, score.openingRetained, .000001)
        assertEquals(1.0, score.endingRetained, .000001)
        assertEquals(0.0, score.gainDb, .000001)
        assertThrows(IllegalArgumentException::class.java) {
            scoreTurns(ShortArray(16000), ShortArray(16000), listOf(VoiceSpan(0, 16000, "zh")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            scoreTurns(clean, ShortArray(2), listOf(VoiceSpan(0, 16000, "zh")))
        }
    }
    @Test fun similarityDistinguishesGainFromLostContent() {
        val clean = ShortArray(1000) { ((it % 37 - 18) * 100).toShort() }
        val quieter = ShortArray(clean.size) { (clean[it] / 2).toShort() }
        val damaged = quieter.copyOf().apply { fill(0, 0, size / 2) }
        assertTrue(waveformSimilarity(clean, quieter) > 80)
        assertTrue(waveformSimilarity(clean, damaged) < 3)
    }
}
