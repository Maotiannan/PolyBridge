package dev.polybridge.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Confirm echoes whose consonants disappear in the inexpensive 4 kHz matcher.
 * Second differences keep steady low-frequency room sound from dominating alignment. The final
 * decision uses the original waveform: an independent voice must not be hidden by pre-emphasis.
 * This class only supplies evidence of an echo; it does not decide when a person interrupts.
 */
internal class FullBandEcho(private val reference: ShortArray, private val backgroundRms: Double) {
    private data class Band(val raw: DoubleArray, val emphasized: DoubleArray, val energy: DoubleArray)
    private data class Window(val start: Int, val frameSize: Int, val bands: Array<Band>)
    private var rate = 0
    private val windows = LinkedHashMap<Int, Window>()
    private var input = DoubleArray(0)
    private var emphasized = DoubleArray(0)
    private var inputEnergy = DoubleArray(0)

    private fun window(offset: Int, frameSize: Int, sampleRate: Int): Window {
        if (rate != sampleRate) { windows.clear(); rate = sampleRate }
        val start = offset / BLOCK_SAMPLES * BLOCK_SAMPLES
        windows[start]?.takeIf { it.frameSize >= frameSize }?.let { return it }
        val end = minOf(reference.size, start + BLOCK_SAMPLES + (frameSize + 2) * 3 / 2 + 3)
        // Three phases cover every possible 24 kHz reference offset at a 16 kHz capture clock.
        // For a 48 kHz capture, average the same three samples as the input downsampling below.
        // Keep the legacy sample-hold reference and a linear-resampled reference. Hardware
        // resampling can smooth alternate 24->16 kHz samples; treating that as an unrelated
        // consonant leaves intelligible echo for the speech enhancer to amplify.
        val bands = Array(6) { variant ->
            val phase = variant % 3
            val linear = variant >= 3
            val raw = DoubleArray(((end - start - phase - 2) * 2 / 3).coerceAtLeast(0)) { i ->
                val pos = start + phase + i * 3 / 2
                if (linear) {
                    // Also matches captures already resampled at 16 kHz before delivery.
                    if (i % 2 == 0) reference[pos].toDouble() else (reference[pos] + reference[pos + 1]) / 2.0
                } else if (sampleRate == 48000) {
                    val left = if (i % 2 == 0) 2 else 1
                    (reference[pos] * left + reference[pos + 1] * (3 - left)) / 3.0
                } else reference[pos].toDouble()
            }
            val high = DoubleArray(raw.size) { i -> if (i < 2) 0.0 else raw[i] - 2 * raw[i - 1] + raw[i - 2] }
            val energy = DoubleArray(raw.size + 1)
            for (i in high.indices) energy[i + 1] = energy[i] + high[i] * high[i]
            Band(raw, high, energy)
        }
        // Cache only nearby half-second blocks, including the frame overlap at their edges.
        // A long translated utterance must not allocate full-length copies on the audio worker.
        val result = Window(start, frameSize, bands)
        windows[start] = result
        while (windows.size > 2) windows.remove(windows.keys.first())
        return result
    }

    /** Search bounds and preferred offset are in the original 24 kHz playback clock. */
    fun confirms(samples: ShortArray, count: Int, sampleRate: Int, from: Int, to: Int, preferred: Int): Boolean {
        if (sampleRate != 16000 && sampleRate != 48000 || from > to) return false
        val stride = sampleRate / 16000
        val n = count / stride
        if (n < 32) return false
        if (input.size < n) { input = DoubleArray(n); emphasized = DoubleArray(n); inputEnergy = DoubleArray(n + 1) }
        var highPower = 0.0
        for (i in 0 until n) {
            var sum = 0
            for (j in 0 until stride) sum += samples[i * stride + j]
            input[i] = sum.toDouble() / stride
            if (i > 1) {
                emphasized[i] = input[i] - 2 * input[i - 1] + input[i - 2]
                highPower += emphasized[i] * emphasized[i]
            }
            inputEnergy[i + 1] = highPower
        }
        if (highPower < n * 16.0) return false
        val room = backgroundRms * 1.25 + 18
        val maximumResidual = room * room * n

        fun matches(offset: Int, linear: Boolean): Boolean {
            if (offset < 0 || offset < from || offset > to || offset >= reference.size) return false
            val window = window(offset, n, sampleRate)
            val band = window.bands[offset % 3 + if (linear) 3 else 0]
            val start = (offset - window.start) / 3 * 2
            if (start + n > band.raw.size) return false
            val refPower = band.energy[start + n] - band.energy[start + 2]
            if (refPower < n * 16.0) return false
            var dot = 0.0
            val requiredDot = sqrt(MIN_EXPLAINED * highPower * refPower)
            var checked = 2
            while (checked < n) {
                val end = minOf(n, checked + CORRELATION_BLOCK)
                for (i in checked until end) dot += emphasized[i] * band.emphasized[start + i]
                checked = end
                if (checked < n) {
                    // Cauchy-Schwarz bounds the *best possible* remaining correlation.
                    // Abandon an impossible alignment, not a quiet or low-confidence voice.
                    // This keeps exactly the same full-search acceptance threshold while
                    // avoiding millions of useless multiplies on every near-end frame.
                    val remainingInput = maxOf(0.0, highPower - inputEnergy[checked])
                    val remainingReference = maxOf(0.0, band.energy[start + n] - band.energy[start + checked])
                    if (abs(dot) + sqrt(remainingInput * remainingReference) < requiredDot * (1 - 1e-10)) return false
                }
            }
            if (dot * dot < MIN_EXPLAINED * highPower * refPower) return false
            val gain = dot / refPower
            if (abs(gain) > 2.0) return false
            var residual = 0.0
            for (i in 0 until n) {
                val value = input[i] - gain * band.raw[start + i]
                residual += value * value
                if (residual > maximumResidual) return false
            }
            // A consonant may contribute little total energy beside a low-frequency hum.
            // Its aligned high-band waveform and the measured noise budget are the evidence;
            // requiring a large reduction of total power would lose precisely these echoes.
            return true
        }

        // Usually the low-band match is already close. Only search the full allowed acoustic
        // delay when that hint cannot explain the full waveform.
        for (delta in 0..18) {
            for (variant in 0..1) {
                if (matches(preferred + delta, variant == 1) || delta > 0 && matches(preferred - delta, variant == 1)) return true
            }
        }
        for (offset in from.coerceAtLeast(0)..to) if (matches(offset, false) || matches(offset, true)) return true
        return false
    }

    private companion object {
        const val MIN_EXPLAINED = .90
        const val BLOCK_SAMPLES = 12000
        const val CORRELATION_BLOCK = 32
    }
}
