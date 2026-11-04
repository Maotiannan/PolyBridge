package dev.polybridge.qwen

import dev.polybridge.core.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

internal data class InputPcm(val samples: ShortArray, val rate: Int)

internal fun readWave(wave: ByteArray): InputPcm {
    require(wave.size in 44..(120 * 48000 * 2 + 4096))
    fun tag(at: Int) = String(wave, at, 4, Charsets.US_ASCII)
    require(tag(0) == "RIFF" && tag(8) == "WAVE")
    val data = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var rate = 0
    while (offset <= wave.size - 8) {
        val size = data.getInt(offset + 4)
        require(size >= 0 && size <= wave.size - offset - 8)
        when (tag(offset)) {
            "fmt " -> {
                require(size >= 16 && data.getShort(offset + 8).toInt() == 1 && data.getShort(offset + 10).toInt() == 1)
                require(data.getShort(offset + 22).toInt() == 16)
                rate = data.getInt(offset + 12)
                require(rate == 16000 || rate == 48000)
            }
            "data" -> {
                require(rate != 0 && size > 0 && size % 2 == 0)
                return InputPcm(ShortArray(size / 2) { data.getShort(offset + 8 + it * 2) }, rate)
            }
        }
        offset += 8 + size + (size and 1)
    }
    throw IllegalArgumentException("Missing PCM data")
}

internal fun collectUtterance(side: Side, wave: ByteArray, audio: Boolean,
    sourceReady: (String) -> Unit = {}, factory: ((LiveUpdate) -> Unit) -> LiveSpeechSession): SpeechResult {
    val input = readWave(wave)
    val lines = Collections.synchronizedMap(linkedMapOf<String, LiveUpdate.Finished>())
    val error = AtomicReference<TranslationIssue?>()
    val link = factory { event ->
        when (event) {
            is LiveUpdate.Finished -> if (event.side == side) {
                lines.putIfAbsent(event.id, event)
                event.issue?.let { error.compareAndSet(null, it) }
            }
            is LiveUpdate.Stopped -> error.compareAndSet(null, event.issue)
            else -> Unit
        }
    }
    link.use {
        it.connect()
        var offset = 0
        while (offset < input.samples.size) {
            if (Thread.currentThread().isInterrupted) throw TranslationFailure(TranslationIssue.INTERRUPTED)
            error.get()?.let { reason -> throw TranslationFailure(reason) }
            val next = minOf(offset + input.rate / 10, input.samples.size)
            val frame = input.samples.copyOfRange(offset, next)
            it.send(frame, frame.size, input.rate)
            offset = next
        }
        it.finish()
    }
    val completed = synchronized(lines) { lines.values.sortedBy { it.startedMs } }
    val source = completed.joinToString(" ") { it.source }.trim()
    if (source.isNotBlank()) sourceReady(source)
    error.get()?.let { throw TranslationFailure(it) }
    if (completed.isEmpty() || completed.any { it.source.isBlank() || it.target.isBlank() }) throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
    val sound = ByteArrayOutputStream()
    val rate = completed.first().pcmRate
    if (audio && completed.all { it.pcm.isNotEmpty() && it.pcmRate == rate }) {
        for (line in completed) {
            if (sound.size() + line.pcm.size > 8 * 1024 * 1024) throw TranslationFailure(TranslationIssue.LIMIT)
            sound.write(line.pcm)
        }
    }
    return SpeechResult(source, completed.joinToString(" ") { it.target }.trim(),
        if (sound.size() == 0) byteArrayOf() else waveFromPcm(rate, sound.toByteArray()), completed.last().model)
}
