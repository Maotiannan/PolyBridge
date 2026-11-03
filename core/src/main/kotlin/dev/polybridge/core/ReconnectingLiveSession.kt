package dev.polybridge.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Audio from an interrupted connection is never replayed into a new subtitle context. */
class ReconnectingLiveSession(private val factory: ((LiveUpdate) -> Unit) -> LiveSpeechSession,
    private val update: (LiveUpdate) -> Unit, private val delays: LongArray = longArrayOf(700, 1800, 3500)) : LiveSpeechSession {
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "translation-reconnect").apply { isDaemon = true } }
    private var generation = 0
    private var current: LiveSpeechSession? = null
    private var opening: LiveSpeechSession? = null
    private var closed = false
    private var ending = false
    private var terminal: TranslationIssue? = null

    override fun connect() { open(initial = true, attempt = 0) }

    private fun open(initial: Boolean, attempt: Int) {
        val epoch: Int
        val link: LiveSpeechSession
        synchronized(this) {
            if (closed || ending) return
            epoch = ++generation
            link = factory { event -> receive(epoch, event) }
            opening = link
        }
        try {
            link.connect()
            synchronized(this) {
                if (epoch != generation || closed || ending) { link.close(); return }
                opening = null
                current = link
                if (!initial) update(LiveUpdate.Reconnected)
            }
        } catch (failure: Exception) {
            link.close()
            val reason = (failure as? TranslationFailure)?.issue ?: TranslationIssue.NETWORK
            synchronized(this) {
                if (epoch != generation || closed || ending) return
                opening = null
                if (initial) throw TranslationFailure(reason)
                if (reason == TranslationIssue.NETWORK && attempt + 1 < delays.size) schedule(attempt + 1)
                else fail(reason)
            }
        }
    }

    @Synchronized private fun receive(epoch: Int, event: LiveUpdate) {
        if (closed || epoch != generation) return
        if (event is LiveUpdate.Stopped) {
            // During handshake connect() will raise the same error on the caller.
            if (current != null && !ending) lost(event.issue)
        } else update(event)
    }

    @Synchronized private fun lost(reason: TranslationIssue) {
        if (closed || ending || current == null) return
        generation++
        val failed = current
        current = null
        failed?.close()
        if (reason == TranslationIssue.NETWORK && delays.isNotEmpty()) schedule(0) else fail(reason)
    }

    private fun schedule(attempt: Int) {
        update(LiveUpdate.Reconnecting(attempt + 1))
        worker.schedule({ open(initial = false, attempt = attempt) }, delays[attempt], TimeUnit.MILLISECONDS)
    }

    private fun fail(reason: TranslationIssue) {
        terminal = reason
        update(LiveUpdate.Stopped(reason))
    }

    override fun send(samples: ShortArray, length: Int, rate: Int) {
        val link = synchronized(this) {
            terminal?.let { throw TranslationFailure(it) }
            if (closed || ending) return
            current
        } ?: return
        try { link.send(samples, length, rate) }
        catch (failed: TranslationFailure) { synchronized(this) { if (current === link) lost(failed.issue) } }
    }

    override fun finish() {
        val link = synchronized(this) { ending = true; current }
        link?.finish()
    }

    @Synchronized override fun close() {
        closed = true
        generation++
        current?.close(); opening?.close()
        current = null; opening = null
        worker.shutdownNow()
    }
}
