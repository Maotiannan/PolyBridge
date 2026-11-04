package dev.polybridge.qwen

import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test

class UtteranceTest {
    @Test fun oneHeldRecordingKeepsEverySentenceAndUsesActualAudioRate() {
        val fragments = listOf(
            LiveUpdate.Finished("a", Side.FRENCH, 0, "Bonjour.", "你好。", byteArrayOf(1, 0), pcmRate = 24000),
            LiveUpdate.Finished("b", Side.FRENCH, 1200, "Deux billets.", "两张票。", byteArrayOf(2, 0), pcmRate = 24000))
        val result = collectUtterance(Side.FRENCH, waveFromPcm(16000, ByteArray(3200)), true) { receive ->
            object : LiveSpeechSession {
                override fun connect() = Unit
                override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
                override fun finish() { fragments.forEach(receive) }
                override fun close() = Unit
            }
        }
        assertEquals("Bonjour. Deux billets.", result.source)
        assertEquals("你好。 两张票。", result.target)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), result.wave.takeLast(4).toByteArray())
    }
    @Test fun failedOrUntranslatedFragmentsCannotBecomeASuccessfulHeldMessage() {
        assertThrows(TranslationFailure::class.java) {
            collectUtterance(Side.FRENCH, waveFromPcm(16000, ByteArray(3200)), false) { receive ->
                object : LiveSpeechSession {
                    override fun connect() = Unit
                    override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
                    override fun finish() { receive(LiveUpdate.Finished("a", Side.FRENCH, 0, "Bonjour.", "", byteArrayOf(), TranslationIssue.EMPTY_REPLY)) }
                    override fun close() = Unit
                }
            }
        }
    }
}
