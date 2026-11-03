package dev.polybridge.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface SpeechTranslation {
    fun transcribe(side: Side, wave: ByteArray): String
    fun translate(side: Side, source: String): String
}

interface VoiceSynthesis {
    fun synthesize(language: String, text: String): ByteArray
}

enum class TranslationIssue { CREDENTIAL, LIMIT, NETWORK, SERVICE, EMPTY_REPLY, INTERRUPTED }
class TranslationFailure(val issue: TranslationIssue) : Exception(issue.name)

/** A completed cloud stream can still carry provisional RIFF sizes. Android uses these to calculate duration. */
fun playableWave(bytes: ByteArray): ByteArray {
    fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
    require(bytes.size >= 44 && tag(0) == "RIFF" && tag(8) == "WAVE")
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var chunk = 12
    while (chunk <= bytes.size - 8) {
        val reported = header.getInt(chunk + 4).toLong() and 0xffffffffL
        val available = bytes.size - chunk - 8
        if (tag(chunk) == "data") {
            require(available > 0)
            header.putInt(4, bytes.size - 8)
            if (reported > available || reported == 0L) header.putInt(chunk + 4, available)
            return bytes
        }
        require(reported <= available)
        chunk += 8 + reported.toInt() + (reported.toInt() and 1)
    }
    throw IllegalArgumentException("Missing WAVE data chunk")
}

/** One user-held utterance, 16-bit signed little-endian mono, bounded to the recorder's limit. */
class MonoWave(private val rate: Int) {
    private val pcm = ByteArrayOutputStream()
    init { require(rate in 8000..48000) }

    fun append(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        val remaining = (rate * 30 - pcm.size() / 2).coerceAtLeast(0)
        val n = minOf(count, remaining)
        for (i in 0 until n) {
            val sample = samples[i].toInt()
            pcm.write(sample and 255)
            pcm.write(sample ushr 8 and 255)
        }
    }

    fun bytes(): ByteArray {
        val size = pcm.size()
        val buffer = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(value: String) { buffer.put(value.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); buffer.putInt(36 + size); tag("WAVE"); tag("fmt ")
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1); buffer.putInt(rate)
        buffer.putInt(rate * 2); buffer.putShort(2); buffer.putShort(16)
        tag("data"); buffer.putInt(size); buffer.put(pcm.toByteArray())
        return buffer.array()
    }
}
