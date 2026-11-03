package dev.polybridge.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Match the microphone against the PCM actually sent to the player. A short search around the
 * playhead covers device buffering and room reflections. Keep unrelated speech from its first frame.
 * The caller owns the microphone buffer and sends it immediately after filter returns.
 */
class PlaybackEcho(private val backgroundRms: Double = 0.0) {
    private var reference = ShortArray(0)
    private var envelope = IntArray(0)
    private var energy = DoubleArray(0)
    private var analysis = IntArray(128)
    private var anchor = -1L
    private var lastPosition = -1
    private var stopped = -1L
    private var lastPlayed = 0
    private var voicedMs = 0.0
    private var interrupted = false
    var weakVoice = false
        private set
    private var pathDelay: Int? = null
    private var pathGain = 0.0
    private var reflectionDelay = 0
    private var reflectionGain = 0.0
    private var matchedAt = -1L
    private var matchedOffset = 0

    @Synchronized fun start(pcm: ByteArray) {
        reference = ShortArray(pcm.size / 2) { i ->
            ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
        }
        // Six 24 kHz samples per 4 kHz analysis bin. Prefix sums normalize a candidate delay in O(1).
        envelope = IntArray(reference.size / 6) { i -> (0..5).sumOf { reference[6 * i + it].toInt() } / 6 }
        energy = DoubleArray(envelope.size + 1)
        for (i in envelope.indices) energy[i + 1] = energy[i] + envelope[i].toDouble() * envelope[i]
        anchor = -1; lastPosition = -1; stopped = -1; voicedMs = 0.0; interrupted = false
        pathDelay = null; pathGain = 0.0; reflectionGain = 0.0; matchedAt = -1
    }

    @Synchronized fun position(milliseconds: Int, nowMs: Long) {
        if (stopped < 0 && milliseconds != lastPosition) {
            anchor = nowMs - milliseconds
            lastPosition = milliseconds
        }
    }

    @Synchronized fun stop(nowMs: Long) {
        if (stopped >= 0) return
        stopped = nowMs
        lastPlayed = if (anchor < 0) 0 else ((nowMs - anchor) * 4).toInt().coerceIn(0, envelope.size)
    }

    @Synchronized fun listenAgain() { interrupted = false; voicedMs = 0.0 }

    @Synchronized fun filter(samples: ShortArray, count: Int, rate: Int, nowMs: Long): Boolean {
        weakVoice = false
        if (anchor < 0 || nowMs < anchor || reference.isEmpty() || count <= 0) return false
        if (stopped >= 0 && nowMs - stopped > TAIL_MS) {
            reference = ShortArray(0); envelope = IntArray(0); energy = DoubleArray(0)
            return false
        }
        val stride = rate / 4000
        if (stride <= 0) return false
        val n = count / stride
        // AudioRecord's nonblocking reads can return the last few samples of a hardware packet.
        if (n < 8) return false
        if (analysis.size < n) analysis = IntArray(n)
        var power = 0.0
        for (i in 0 until n) {
            var sum = 0
            for (j in 0 until stride) sum += samples[i * stride + j].toInt()
            analysis[i] = sum / stride
            power += analysis[i].toDouble() * analysis[i]
        }
        val rms = sqrt(power / n)
        val cursor = ((nowMs - anchor) * 4).toInt() - n
        val limit = if (stopped >= 0) lastPlayed else envelope.size
        var best = 0.0
        var bestOffset = 0
        var scale = 0.0
        val predicted = if (matchedAt >= 0) matchedOffset + ((nowMs - matchedAt) * 4).toInt() else cursor
        if (rms > 42) {
            val from = max(0, minOf(cursor - TAIL_MS * 4, predicted - 100))
            val to = minOf(limit - n, maxOf(cursor + 180 * 4, predicted + 100))
            for (offset in from..to) {
                val refPower = energy[offset + n] - energy[offset]
                if (refPower < 16.0 * n) continue
                var dot = 0.0
                for (i in 0 until n) dot += analysis[i].toDouble() * envelope[offset + i]
                val explained = dot * dot / refPower
                if (explained > best) { best = explained; bestOffset = offset; scale = dot / refPower }
            }
        }
        val fraction = if (power > 0) best / power else 0.0
        val delay = cursor - bestOffset
        val previous = pathDelay
        // The Android playhead is quantized and can jump when a Bluetooth route settles. The
        // matching waveform and its acoustic gain survive that jump; the old numeric delay does not.
        val plausibleGain = abs(scale - pathGain) <= max(.025, abs(pathGain) * .55)
        val consistent = previous == null || (plausibleGain && (abs(delay - previous) <= 32 || fraction >= .87))
        if (fraction >= .90 && consistent && abs(scale) <= 2.0) {
            pathDelay = delay
            matchedAt = nowMs
            matchedOffset = bestOffset
            pathGain = if (previous == null) scale else .8 * pathGain + .2 * scale
            // Learn one reflected path from the remainder only on a frame dominated by our own voice.
            var residualPower = 0.0
            for (i in 0 until n) {
                val value = analysis[i] - pathGain * envelope[bestOffset + i]
                residualPower += value * value
            }
            var reflected = .65 * residualPower
            for (lag in 8..minOf(200, bestOffset)) {
                val offset = bestOffset - lag
                val refPower = energy[offset + n] - energy[offset]
                if (refPower < 16.0 * n) continue
                var dot = 0.0
                for (i in 0 until n) dot += (analysis[i] - pathGain * envelope[bestOffset + i]) * envelope[offset + i]
                val explained = dot * dot / refPower
                val gain = dot / refPower
                if (explained > reflected && abs(gain) <= abs(pathGain) * .65) {
                    reflected = explained; reflectionDelay = lag; reflectionGain = gain
                }
            }
        }
        val offset = if (consistent && fraction >= .75) bestOffset else predicted
        var remainder = power
        if (pathDelay != null) {
            remainder = 0.0
            for (i in 0 until n) {
                val pos = offset + i
                val direct = if (pos in 0 until limit) envelope[pos] else 0
                val late = pos - reflectionDelay
                val reflected = if (late in 0 until limit) envelope[late] else 0
                val value = analysis[i] - pathGain * direct - reflectionGain * reflected
                remainder += value * value
            }
        }
        val floor = max(90.0, backgroundRms * 1.65 + 18)
        val matchedEcho = pathDelay != null && (remainder < power * .12 || (consistent && fraction >= .92))
        // Quiet vowels can sit below the local interruption threshold. Send their residual to ASR
        // so a new language/utterance can still take the floor. A steady tone alone is insufficient.
        weakVoice = !matchedEcho && rms >= max(42.0, backgroundRms * .65) && voicedShape(n, rms)
        val roomOnly = !weakVoice && backgroundRms > 0 && !interrupted && voicedMs == 0.0 && sqrt(minOf(remainder, power) / n) < floor
        val echoOnly = rms <= 42 || roomOnly || matchedEcho
        if (echoOnly) samples.fill(0, 0, count)
        else if (pathDelay != null && remainder < power) {
            for (i in 0 until count) {
                val pos = offset * 6 + i * 24000 / rate
                val direct = if (pos in 0 until limit * 6) reference[pos].toInt() else 0
                val late = pos - reflectionDelay * 6
                val reflected = if (late in 0 until limit * 6) reference[late].toInt() else 0
                samples[i] = (samples[i] - pathGain * direct - reflectionGain * reflected).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        if (interrupted || stopped >= 0) return false
        val duration = count * 1000.0 / rate
        val independent = !echoOnly && sqrt(minOf(remainder, power) / n) >= floor
        voicedMs = if (independent) voicedMs + duration else max(0.0, voicedMs - duration)
        if (voicedMs < 55) return false
        interrupted = true
        return true
    }

    private companion object { const val TAIL_MS = 340 }

    private fun voicedShape(n: Int, rms: Double): Boolean {
        if (n < 40 || (0 until n).maxOf { abs(analysis[it]) } < rms * 1.72) return false
        for (lag in 12..minOf(42, n / 2)) {
            var dot = 0.0; var left = 0.0; var right = 0.0
            for (i in lag until n) {
                val a = analysis[i].toDouble(); val b = analysis[i - lag].toDouble()
                dot += a * b; left += a * a; right += b * b
            }
            if (dot > 0 && dot * dot > .29 * left * right) return true
        }
        return false
    }
}

/** A rolling percentile tracks ordinary room fluctuations while discounting short loud peaks. */
class RoomNoise {
    private val recent = DoubleArray(100)
    private var total = 0
    @Synchronized fun observe(samples: ShortArray, n: Int, rate: Int) {
        if (n <= 0) return
        recent[total++ % recent.size] = rms(samples, n, rate)
    }
    companion object {
        fun rms(samples: ShortArray, n: Int, rate: Int): Double {
            val stride = max(1, rate / 4000)
            var power = 0.0
            var count = 0
            var i = 0
            while (i + stride <= n) {
                var sum = 0
                for (j in 0 until stride) sum += samples[i + j].toInt()
                val mean = sum.toDouble() / stride
                power += mean * mean
                count++; i += stride
            }
            return if (count > 0) sqrt(power / count) else 0.0
        }
    }
    @Synchronized fun level(): Double {
        val values = recent.copyOf(minOf(total, recent.size)).sortedArray()
        return if (values.size < 5) 0.0 else values[values.size * 2 / 3]
    }
}
