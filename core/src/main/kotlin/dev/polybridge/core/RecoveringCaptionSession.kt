package dev.polybridge.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RecoveredCaption(val side: Side, val source: String, val target: String, val model: ModelTrace? = null)
data class CaptionRecoveryPolicy(val bufferMs: Int = 90_000, val maximumClipMs: Int = 60_000,
    val maximumPending: Int = 2, val finishWaitMs: Long = 6_000)

/** Owns temporary audio for this connection only. */
class RecoveringCaptionSession(factory: ((LiveUpdate) -> Unit) -> LiveSpeechSession,
    private val recover: (ByteArray) -> RecoveredCaption, private val update: (LiveUpdate) -> Unit,
    private val policy: CaptionRecoveryPolicy = CaptionRecoveryPolicy()) : LiveSpeechSession {
    private data class Pending(val original: LiveUpdate.Finished, val wave: ByteArray,
        val done: CountDownLatch = CountDownLatch(1), var running: Boolean = false, var completing: Boolean = false)
    private val lock = Any()
    private val audio = ShortArray(policy.bufferMs * 16)
    private var samplesSent = 0L
    private val seen = linkedSetOf<Pair<Side, String>>()
    private val pending = linkedMapOf<Pair<Side, String>, Pending>()
    @Volatile private var closed = false
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "caption-recovery").apply { isDaemon = true } }
    private val link = factory(::receive)

    init {
        require(policy.bufferMs in 1..120_000 && policy.maximumClipMs in 1..policy.bufferMs)
        require(policy.maximumPending in 1..4 && policy.finishWaitMs in 0..30_000)
    }

    override fun connect() = link.connect()

    override fun send(samples: ShortArray, length: Int, rate: Int) {
        require(length in 0..samples.size && rate in setOf(16000, 48000))
        synchronized(lock) {
            if (closed) return
            // Match the live transport's downsampling and clock, including silent frames.
            val stride = rate / 16000
            var i = 0
            while (i + stride <= length) {
                var sum = 0
                repeat(stride) { sum += samples[i++].toInt() }
                audio[(samplesSent++ % audio.size).toInt()] = (sum / stride).toShort()
            }
        }
        link.send(samples, length, rate)
    }

    private fun emit(event: LiveUpdate) { if (!closed) update(event) }

    private fun receive(event: LiveUpdate) {
        if (closed) return
        if (event !is LiveUpdate.Finished) { emit(event); return }
        val key = event.side to event.id
        val work = synchronized(lock) {
            if (closed || !seen.add(key)) return
            if (seen.size > 512) seen.remove(seen.first())
            if (event.issue != TranslationIssue.UNRELIABLE) null
            else if (pending.size >= policy.maximumPending) null else clip(event)?.let { wave ->
                Pending(event, wave).also { pending[key] = it }
            }
        }
        if (work == null) { emit(event); return }
        emit(LiveUpdate.Caption(event.id, event.side, event.source, "", event.startedMs, TranslationIssue.UNRELIABLE))
        synchronized(lock) {
            if (closed) { work.wave.fill(0); return }
            worker.execute {
                try {
                    synchronized(lock) { if (closed || pending[key] !== work) return@execute; work.running = true }
                    val result = runCatching { recover(work.wave) }.getOrNull()?.takeIf {
                        it.source.isNotBlank() && it.target.isNotBlank() &&
                            !CaptionQuality.repetitive(it.source) && !CaptionQuality.repetitive(it.target)
                    }
                    complete(key, work, result)
                } finally { work.wave.fill(0); work.done.countDown() }
            }
        }
    }

    /** Never certify a guess made from a clipped tail, a missing boundary, or expired audio. */
    private fun clip(event: LiveUpdate.Finished): ByteArray? {
        val endMs = event.endedMs ?: return null
        if (event.startedMs < 0 || endMs <= event.startedMs || endMs - event.startedMs > policy.maximumClipMs) return null
        val start = event.startedMs * 16
        val end = endMs * 16
        if (start < (samplesSent - audio.size).coerceAtLeast(0) || end > samplesSent) return null
        val clip = ShortArray((end - start).toInt()) { audio[((start + it) % audio.size).toInt()] }
        return MonoWave(16000, (policy.maximumClipMs + 999) / 1000).apply { append(clip, clip.size) }
            .bytes().also { clip.fill(0) }
    }

    private fun complete(key: Pair<Side, String>, work: Pending, result: RecoveredCaption?) {
        synchronized(lock) {
            if (closed || pending[key] !== work || work.completing) return
            work.completing = true
        }
        try {
            if (result == null) emit(work.original)
            else {
                emit(LiveUpdate.Withdrawn(work.original.id, work.original.side))
                emit(work.original.copy(side = result.side, source = result.source, target = result.target,
                    pcm = byteArrayOf(), issue = null, model = result.model ?: work.original.model))
            }
        } finally {
            synchronized(lock) { if (pending[key] === work) pending.remove(key) }
            work.done.countDown()
        }
    }

    override fun finish() {
        try { link.finish() }
        finally {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.finishWaitMs)
            val remaining = synchronized(lock) { pending.toMap() }
            for ((key, work) in remaining) {
                work.done.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                complete(key, work, null)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            audio.fill(0)
            pending.values.forEach { if (!it.running) it.wave.fill(0); it.done.countDown() }
            pending.clear(); seen.clear()
            // Running work erases its own clip; discard queued work without sending more audio.
            worker.shutdownNow()
        }
        link.close()
    }
}
