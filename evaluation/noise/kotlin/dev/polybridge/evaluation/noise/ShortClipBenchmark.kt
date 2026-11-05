package dev.polybridge.evaluation.noise

import com.google.gson.Gson
import dev.polybridge.core.LiveSpeechSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*

data class ClipDescription(val id: String, val file: String, val sha256: String, val reference: String?,
    val referenceSha256: String?, val sampleRate: Int, val samples: Int, val turns: List<VoiceSpan>)
data class ClipManifest(val schemaVersion: Int, val clips: List<ClipDescription>)
data class ClipMeasurement(val clip: String, val enhanced: Boolean, val seconds: Double,
    val firstNonzeroAtInputMs: Double?, val maxBufferedMs: Double, val beforeFinishSamples: Int,
    val elapsedMs: Double, val frameP95Ms: Double, val frameMaxMs: Double,
    val outputNonzeroFraction: Double, val suppressionDb: Double, val turns: List<TurnScore>,
    val acousticIssues: List<String>)
data class ClipReport(val platform: String, val results: List<ClipMeasurement>,
    val schemaVersion: Int = 1, val cloudCalls: Int = 0,
    val scope: String = "Shipping audio filter only; unpaced fixed input; no capture, echo, cloud recognition or translation accuracy")

fun readManifest(json: String): ClipManifest = Gson().fromJson(json, ClipManifest::class.java).also {
    require(it.schemaVersion == 1 && it.clips.isNotEmpty())
    require(it.clips.map { clip -> clip.id }.distinct().size == it.clips.size)
}

private fun checkedAudio(file: String, hash: String, read: (String) -> ByteArray): ShortArray {
    require(file.matches(Regex("[a-z0-9.-]+\\.wav")))
    val bytes = read(file)
    val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    require(actual == hash) { "fixture checksum changed: $file" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 8, Charsets.US_ASCII) == "WAVEfmt " && buffer.getInt(16) == 16 &&
        buffer.getShort(20).toInt() == 1 && buffer.getShort(22).toInt() == 1 && buffer.getInt(24) == 16000 &&
        buffer.getShort(34).toInt() == 16 && String(bytes, 36, 4, Charsets.US_ASCII) == "data" &&
        buffer.getInt(40) == bytes.size - 44 && (bytes.size - 44) % 2 == 0) { "expected canonical mono PCM16 fixture: $file" }
    return ShortArray((bytes.size - 44) / 2) { buffer.getShort(44 + it * 2) }
}

/** Factories inject the real JVM SpeechFilter or Android SpeechGate. The sink never connects to a provider. */
fun evaluateClip(clip: ClipDescription, enhanced: Boolean, read: (String) -> ByteArray,
    create: (LiveSpeechSession, Boolean) -> LiveSpeechSession,
    saveOutput: (ShortArray) -> Unit = {}): ClipMeasurement {
    val input = checkedAudio(clip.file, clip.sha256, read)
    require(clip.sampleRate == 16000 && clip.samples == input.size && input.size in 1..320000)
    val clean = clip.reference?.let { checkedAudio(it, requireNotNull(clip.referenceSha256), read) }
    require((clean == null) == clip.turns.isEmpty())
    val output = ShortArray(input.size)
    var received = 0; var sent = 0; var finishes = 0; var closes = 0
    var firstNonzero: Int? = null
    var maxBuffered = 0
    val times = ArrayList<Double>()
    val sink = object : LiveSpeechSession {
        override fun connect() = Unit
        override fun send(samples: ShortArray, length: Int, rate: Int) {
            check(rate == 16000 && length in 0..samples.size && received + length <= output.size)
            if (firstNonzero == null && (0 until length).any { samples[it] != 0.toShort() }) firstNonzero = sent
            samples.copyInto(output, received, 0, length); received += length
        }
        override fun finish() { finishes++ }
        override fun close() { closes++ }
    }
    val began = System.nanoTime()
    var beforeFinish: Int
    create(sink, enhanced).use { filter ->
        for (start in input.indices step 320) {
            val block = input.copyOfRange(start, minOf(start + 320, input.size))
            sent += block.size
            val frameStart = System.nanoTime()
            filter.send(block, block.size, 16000)
            times += (System.nanoTime() - frameStart) / 1e6
            maxBuffered = max(maxBuffered, sent - received)
        }
        beforeFinish = received
        check(beforeFinish >= input.size - 8000) { "filter held the clip until finish: ${clip.id}" }
        filter.finish(); filter.finish()
    }
    val elapsed = (System.nanoTime() - began) / 1e6
    check(received == input.size && finishes == 1 && closes == 1) { "clock or lifecycle failure: ${clip.id}" }
    saveOutput(output)
    val turns = clean?.let { scoreTurns(it, output, clip.turns) }.orEmpty()
    val issues = turns.flatMapIndexed { index, score ->
        buildList {
            if (score.retained < .90) add("turn${index + 1}: retained below 90%")
            if (score.openingRetained < .90) add("turn${index + 1}: opening below 90%")
            if (score.endingRetained < .90) add("turn${index + 1}: ending below 90%")
        }
    }.toMutableList()
    val nonzero = output.count { it != 0.toShort() }.toDouble() / output.size
    if (clean == null && nonzero > 0) issues += "noise-only: nonzero audio reached recognition sink"
    times.sort()
    return ClipMeasurement(clip.id, enhanced, input.size / 16000.0, firstNonzero?.div(16.0), maxBuffered / 16.0,
        beforeFinish, elapsed, times[ceil(times.size * .95).toInt() - 1], times.last(), nonzero,
        10 * log10((input.sumOf { it.toDouble().pow(2) } + .001) / (output.sumOf { it.toDouble().pow(2) } + .001)),
        turns, issues)
}
