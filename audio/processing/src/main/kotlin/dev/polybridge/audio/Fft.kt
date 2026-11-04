package dev.polybridge.audio

import kotlin.math.*

/** Fixed-size reusable radix-2 FFT; no frame-sized allocation on the capture path. */
internal class Fft(private val size: Int) {
    private val reverse = IntArray(size) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(size)) }
    private val cosine = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sine = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    init { require(size > 1 && size and (size - 1) == 0) }

    fun transform(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean = false) {
        require(real.size == size && imaginary.size == size)
        for (i in 0 until size) {
            val j = reverse[i]
            if (j > i) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var width = 2
        while (width <= size) {
            val half = width / 2
            val step = size / width
            for (start in 0 until size step width) for (k in 0 until half) {
                val c = cosine[k * step]
                val s = sine[k * step] * if (inverse) 1 else -1
                val left = start + k
                val right = left + half
                val r = real[right] * c - imaginary[right] * s
                val im = real[right] * s + imaginary[right] * c
                real[right] = real[left] - r
                imaginary[right] = imaginary[left] - im
                real[left] += r
                imaginary[left] += im
            }
            width *= 2
        }
        if (inverse) for (i in 0 until size) { real[i] /= size; imaginary[i] /= size }
    }
}
