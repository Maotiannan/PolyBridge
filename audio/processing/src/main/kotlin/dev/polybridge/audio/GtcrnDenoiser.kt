package dev.polybridge.audio

import ai.onnxruntime.OnnxTensor
import kotlin.math.*

/** 16kHz mono. One 256-sample lookahead, bounded buffers, one owner on the capture worker.
 * emit borrows the output array for the duration of the callback. */
class GtcrnDenoiser(model: ByteArray, private val emit: (ShortArray, Int) -> Unit) : AutoCloseable {
    private val engine = OnnxFrames(model, linkedMapOf(
        "mix" to longArrayOf(1, 257, 1, 2), "conv_cache" to longArrayOf(2, 1, 16, 16, 33),
        "tra_cache" to longArrayOf(2, 3, 1, 1, 16), "inter_cache" to longArrayOf(2, 1, 33, 16)))
    private val fft = Fft(512)
    private val window = DoubleArray(512) { sqrt(.5 - .5 * cos(2 * PI * it / 512)) }
    private val analysis = DoubleArray(512)
    private val synthesis = DoubleArray(512)
    private val real = DoubleArray(512)
    private val imaginary = DoubleArray(512)
    private val output = ShortArray(256)
    private var used = 0
    private var received = 0L
    private var emitted = 0L
    private var first = true
    private var finished = false
    private var closed = false

    fun send(samples: ShortArray, length: Int) {
        require(length in 0..samples.size)
        check(!finished && !closed)
        for (i in 0 until length) {
            analysis[256 + used++] = samples[i] / 32768.0
            received++
            if (used == 256) hop()
        }
    }

    private fun hop() {
        for (i in real.indices) { real[i] = analysis[i] * window[i]; imaginary[i] = 0.0 }
        fft.transform(real, imaginary)
        val mix = engine["mix"]
        for (i in 0..256) { mix.put(i * 2, real[i].toFloat()); mix.put(i * 2 + 1, imaginary[i].toFloat()) }
        engine.run { result ->
            val enhanced = (result.get("enh").get() as OnnxTensor).floatBuffer
            for (i in 0..256) {
                real[i] = enhanced.get(2 * i).toDouble(); imaginary[i] = enhanced.get(2 * i + 1).toDouble()
                check(real[i].isFinite() && imaginary[i].isFinite()) { "Non-finite enhanced audio" }
            }
            engine.copy(result, "conv_cache_out", "conv_cache")
            engine.copy(result, "tra_cache_out", "tra_cache")
            engine.copy(result, "inter_cache_out", "inter_cache")
        }
        imaginary[0] = 0.0; imaginary[256] = 0.0
        for (i in 1..255) { real[512 - i] = real[i]; imaginary[512 - i] = -imaginary[i] }
        fft.transform(real, imaginary, inverse = true)
        for (i in real.indices) synthesis[i] += real[i] * window[i]
        val count = if (first) 0 else minOf(256L, received - emitted).toInt()
        for (i in 0 until count) output[i] = (synthesis[i] * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
        first = false
        for (i in 0..255) { analysis[i] = analysis[i + 256]; synthesis[i] = synthesis[i + 256]; synthesis[i + 256] = 0.0 }
        used = 0
        emitted += count
        if (count > 0) emit(output, count)
    }

    fun finish() {
        check(!closed)
        if (finished) return
        finished = true
        if (used > 0) { analysis.fill(0.0, 256 + used); hop() }
        if (emitted < received) { analysis.fill(0.0, 256); hop() }
    }
    override fun close() {
        if (closed) return
        closed = true
        analysis.fill(0.0); synthesis.fill(0.0); output.fill(0)
        engine.close()
    }
}
