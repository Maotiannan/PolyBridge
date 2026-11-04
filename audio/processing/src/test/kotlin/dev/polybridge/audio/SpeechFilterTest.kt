package dev.polybridge.audio

import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

internal open class Sink : LiveSpeechSession {
    val samples = ArrayList<Short>()
    var finishes = 0
    var closes = 0
    override fun connect() = Unit
    override fun send(samples: ShortArray, length: Int, rate: Int) { assertEquals(16000, rate); repeat(length) { this.samples += samples[it] } }
    override fun finish() { finishes++ }
    override fun close() { closes++ }
}
internal fun gate(sink: LiveSpeechSession, enhanced: Boolean) = SpeechFilter(asset("speech-detection/silero-16k.onnx"), sink,
    if (enhanced) asset("noise-reduction/gtcrn.onnx") else null)
internal fun filtered(input: ShortArray, enhanced: Boolean, rate: Int = 16000, block: Int = rate / 50): ShortArray {
    val sink = Sink()
    gate(sink, enhanced).use { filter ->
        for (start in input.indices step block) {
            val chunk = input.copyOfRange(start, minOf(start + block, input.size))
            filter.send(chunk, chunk.size, rate)
        }
        filter.finish()
    }
    return sink.samples.toShortArray()
}
internal fun siSdr(clean: ShortArray, output: ShortArray): Double {
    val meanC = clean.map { it.toDouble() }.average()
    val meanO = output.map { it.toDouble() }.average()
    var dot = 0.0; var power = 0.0
    for (i in clean.indices) { dot += (clean[i] - meanC) * (output[i] - meanO); power += (clean[i] - meanC).pow(2) }
    val gain = dot / max(power, .001)
    var signal = 0.0; var error = 0.0
    for (i in clean.indices) { val p = (clean[i] - meanC) * gain; signal += p * p; error += (output[i] - meanO - p).pow(2) }
    return 10 * log10((signal + .001) / (error + .001))
}

class SpeechFilterTest {
    @Test fun badModelsAreLocalFailuresAndTransportStillBelongsToCallerIfConstructionFails() {
        val sink = Sink()
        assertThrows(AudioProcessingFailure::class.java) { SpeechFilter(byteArrayOf(0), sink) }
        assertThrows(AudioProcessingFailure::class.java) { SpeechFilter(asset("speech-detection/silero-16k.onnx"), sink, byteArrayOf(0)) }
        assertEquals(0, sink.closes)
        gate(sink, true).use { it.send(ShortArray(320), 320, 16000) }
        assertEquals(1, sink.closes)
    }
    @Test fun finishAndCancellationHaveSeparateIdempotentLifetimes() {
        for (enhanced in listOf(false, true)) {
            val sink = Sink()
            val filter = gate(sink, enhanced)
            assertThrows(IllegalArgumentException::class.java) { filter.send(ShortArray(1), 2, 16000) }
            assertThrows(IllegalArgumentException::class.java) { filter.send(ShortArray(1), 1, 44100) }
            filter.send(ShortArray(513), 513, 16000)
            filter.finish(); filter.finish()
            assertEquals(513, sink.samples.size); assertEquals(1, sink.finishes)
            assertThrows(IllegalStateException::class.java) { filter.send(ShortArray(1), 1, 16000) }
            filter.close(); filter.close()
            assertEquals(1, sink.closes)
            val cancelled = Sink()
            gate(cancelled, enhanced).use { it.send(ShortArray(257), 257, 16000) }
            assertTrue(cancelled.samples.isEmpty()); assertEquals(0, cancelled.finishes); assertEquals(1, cancelled.closes)
        }
    }
    @Test fun quietQuestionsKeepTheirOpeningAfterSilenceAndHum() {
        for (language in listOf("fr", "zh")) {
            val voice = pcm(File(root, "app/src/main/assets/phrasebook/train-$language.wav"))
            val lead = 32000
            val input = ShortArray(lead + voice.size + 16000) { at ->
                ((voice.getOrNull(at - lead) ?: 0) * .14 + 300 * sin(at * 2 * PI * 140 / 16000)).roundToInt().toShort()
            }
            val output = filtered(input, true)
            val first = (0 until minOf(16000, voice.size)).filter { abs(voice[it].toInt()) > 1200 }
            val retained = first.count { output[lead + it] != 0.toShort() }.toDouble() / first.size
            println("quietUninterrupted=$language opening=$retained")
            assertEquals(input.size, output.size)
            assertTrue(output.take(lead - 4000).all { it == 0.toShort() })
            assertTrue("quiet first syllable clipped: $language", retained >= .9)
        }
    }
    @Test fun enhancementImprovesMixedVoiceBeyondTheExistingGate() {
        val rain = pcm(File(root, "app/src/androidTest/assets/rain/2-73027-A-10.wav"))
        for (name in listOf("tickets-fr", "tickets-zh", "train-fr", "train-zh")) {
            val voice = pcm(File(root, "app/src/main/assets/phrasebook/$name.wav"))
            val clean = ShortArray(voice.size) { (voice[it] * .25).roundToInt().toShort() }
            val noiseScale = rms(clean) / rms(rain) / 10.0.pow(5.0 / 20)
            val mixed = ShortArray(voice.size) { (clean[it] + rain[it % rain.size] * noiseScale).roundToInt().toShort() }
            val basic = filtered(mixed, false)
            val enhanced = filtered(mixed, true)
            assertEquals(clean.size, enhanced.size)
            val improvement = siSdr(clean, enhanced) - siSdr(clean, basic)
            println("voice=$name improvementOverGateDb=$improvement")
            assertTrue("enhancement did not improve the actual shipping gate for $name", improvement >= 2)
        }
    }
    @Test fun pureRainNeverEntersRecognitionInEitherMode() {
        for (name in listOf("1-21189-A-10", "2-73027-A-10")) for (enhanced in listOf(false, true)) {
            val rain = pcm(File(root, "app/src/androidTest/assets/rain/$name.wav"))
            val output = filtered(rain, enhanced)
            assertEquals(rain.size, output.size)
            assertTrue("rain leaked mode=$enhanced", output.all { it == 0.toShort() })
        }
    }
    @Test fun bothInputRatesAndFragmentedChunksKeepTheSameAudioClock() {
        val voice = pcm(File(root, "app/src/main/assets/phrasebook/train-fr.wav")).copyOf(16013)
        val repeated = ShortArray(voice.size * 3) { voice[it / 3] }
        for (enhanced in listOf(false, true)) {
            val expected = filtered(voice, enhanced)
            assertEquals(voice.size, expected.size)
            assertArrayEquals(expected, filtered(repeated, enhanced, 48000, 137))
        }
    }
}
