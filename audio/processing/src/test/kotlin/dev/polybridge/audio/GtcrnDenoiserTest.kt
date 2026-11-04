package dev.polybridge.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

internal val root = File(System.getProperty("polybridge.root"))
internal fun asset(name: String) = File(root, "app/src/main/assets/$name").readBytes()
internal fun pcm(path: File): ShortArray {
    val bytes = path.readBytes()
    fun int(at: Int) = (0..3).fold(0) { v, i -> v or ((bytes[at + i].toInt() and 255) shl (8 * i)) }
    var cursor = 12
    var rate = 0
    var start = 0
    var size = 0
    while (cursor + 8 <= bytes.size) {
        val tag = String(bytes, cursor, 4, Charsets.US_ASCII)
        val n = int(cursor + 4)
        if (tag == "fmt ") { require(bytes[cursor + 8] == 1.toByte() && bytes[cursor + 10] == 1.toByte()); rate = int(cursor + 12) }
        if (tag == "data") { start = cursor + 8; size = n; break }
        cursor += 8 + n + n % 2
    }
    require(rate > 0 && start > 0)
    val raw = ShortArray(size / 2) { ((bytes[start + 2 * it].toInt() and 255) or (bytes[start + 2 * it + 1].toInt() shl 8)).toShort() }
    return ShortArray(raw.size * 16000 / rate) { i ->
        val at = i * rate / 16000.0
        val index = at.toInt()
        (raw[index] * (1 - at + index) + raw[minOf(index + 1, raw.lastIndex)] * (at - index)).roundToInt().toShort()
    }
}
internal fun rms(samples: ShortArray) = sqrt(samples.sumOf { it.toDouble() * it } / max(1, samples.size))
internal fun render(samples: ShortArray, block: Int = 320): ShortArray {
    val received = ArrayList<Short>()
    GtcrnDenoiser(asset("noise-reduction/gtcrn.onnx")) { data, n -> repeat(n) { received += data[it] } }.use { filter ->
        for (start in samples.indices step block) {
            val slice = samples.copyOfRange(start, minOf(start + block, samples.size))
            filter.send(slice, slice.size)
        }
        filter.finish()
    }
    return received.toShortArray()
}

class GtcrnDenoiserTest {
    @Test fun matchesIndependentPythonReferenceIncludingAnIncompleteTail() {
        fun fixture(name: String): ShortArray {
            val bytes = javaClass.getResourceAsStream("/$name.pcm")!!.use { it.readBytes() }
            return ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 255) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
        }
        val expected = fixture("gtcrn-expected")
        val actual = render(fixture("gtcrn-input"), 137)
        assertEquals(expected.size, actual.size)
        val error = sqrt(actual.indices.sumOf { (actual[it] - expected[it]).toDouble().pow(2) } / actual.size)
        println("pythonReferencePcmRmsError=$error")
        assertTrue("Kotlin streaming DSP differs from independent reference", error <= 2.0)
    }
    @Test fun shortTailLengthsAndCancellationNeverPadOrFlushUnexpectedAudio() {
        for (size in listOf(0, 1, 255, 256, 257, 511, 512, 513)) {
            assertEquals(size, render(ShortArray(size) { 100 }, 137).size)
        }
        var output = 0
        val filter = GtcrnDenoiser(asset("noise-reduction/gtcrn.onnx")) { _, n -> output += n }
        assertThrows(IllegalArgumentException::class.java) { filter.send(ShortArray(5), 6) }
        filter.send(ShortArray(257), 257)
        assertEquals(0, output)
        filter.close(); filter.close()
        assertEquals(0, output)
        assertThrows(IllegalStateException::class.java) { filter.send(ShortArray(256), 256) }
        assertThrows(IllegalStateException::class.java) { filter.finish() }
    }
    @Test fun actualModelSuppressesBothRainFixturesAndKeepsClock() {
        for (name in listOf("1-21189-A-10", "2-73027-A-10")) {
            val input = pcm(File(root, "app/src/androidTest/assets/rain/$name.wav"))
            val output = render(input)
            assertEquals(input.size, output.size)
            val suppression = 20 * log10(rms(input) / max(.01, rms(output)))
            println("rain=$name suppressionDb=$suppression")
            assertTrue("real noise reduction was bypassed: $suppression dB", suppression >= 10)
        }
    }
    @Test fun chunkBoundariesDoNotChangeResultsAndTailIsNotPadded() {
        val input = pcm(File(root, "app/src/main/assets/phrasebook/train-fr.wav")).copyOf(16013)
        val a = render(input, 137)
        assertEquals(input.size, a.size)
        assertArrayEquals(a, render(input, 960))
        assertTrue(rms(a) > rms(input) * .85)
    }
    @Test fun outputStartsBeforeFinishAndOnlyAfterOneHopLookahead() {
        var count = 0
        GtcrnDenoiser(asset("noise-reduction/gtcrn.onnx")) { _, n -> count += n }.use { filter ->
            filter.send(ShortArray(256), 256)
            assertEquals(0, count)
            filter.send(ShortArray(256), 256)
            assertEquals(256, count)
            filter.finish()
            assertEquals(512, count)
            filter.finish()
            assertEquals(512, count)
        }
    }
}
