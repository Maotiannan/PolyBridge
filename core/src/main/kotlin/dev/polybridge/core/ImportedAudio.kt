package dev.polybridge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Downmix decoded Android PCM and carry the fractional resampling position across decoder buffers. */
class ImportedAudio(private val maxSeconds: Int = 120) {
    private val output = MonoWave(16000, maxSeconds)
    private var phase = 0
    private var sampleTotal = 0L
    private var bucket = 0
    private var bucketCount = 0
    private var previousRate = 0

    fun add(data: ByteBuffer, rate: Int, channels: Int, floating: Boolean) {
        require(rate in 4000..192000 && channels in 1..16)
        data.order(ByteOrder.LITTLE_ENDIAN)
        if (previousRate != rate) { phase = 0; bucket = 0; bucketCount = 0; previousRate = rate }
        val space = channels * if (floating) 4 else 2
        val batch = ShortArray(1024)
        var used = 0
        while (data.remaining() >= space) {
            var combined = 0
            repeat(channels) {
                combined += if (floating) (data.float.coerceIn(-1f, 1f) * 32767).roundToInt() else data.short.toInt()
            }
            bucket += combined / channels
            bucketCount++
            phase += 16000
            if (phase < rate) continue
            val value = (bucket / bucketCount).toShort()
            bucket = 0; bucketCount = 0
            while (phase >= rate) {
                require(++sampleTotal <= maxSeconds.toLong() * 16000) { "DURATION" }
                phase -= rate
                batch[used++] = value
                if (used == batch.size) { output.append(batch, used); used = 0 }
            }
        }
        if (used > 0) output.append(batch, used)
    }

    fun wave(): ByteArray { require(sampleTotal > 0) { "EMPTY" }; return output.bytes() }
}
