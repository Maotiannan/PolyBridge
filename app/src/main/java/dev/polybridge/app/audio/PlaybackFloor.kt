package dev.polybridge.app.audio

import dev.polybridge.core.LiveSpeechSession
import dev.polybridge.core.PlaybackEcho
import dev.polybridge.core.RoomNoise

/** Lives on the microphone thread. Keep the opening of an interruption while checking a possible loudspeaker echo. */
internal class PlaybackFloor {
    private data class Frame(val data: ShortArray, val rate: Int, val possibleVoice: Boolean)
    private val waiting = ArrayDeque<Frame>()
    private val silence = ShortArray(960)
    private var duckRequested = -1L
    private var evidenceMs = 0.0
    private var beforeDuck = 0.0
    private var last: PlaybackEcho? = null

    fun send(samples: ShortArray, n: Int, rate: Int, now: Long, reference: PlaybackEcho?, speaking: Boolean,
        headphones: Boolean, background: Double, mutedAt: Long, link: LiveSpeechSession,
        duck: (Boolean) -> Unit, interrupt: () -> Unit) {
        val candidate = reference?.filter(samples, n, rate, now) == true
        if (last !== reference || !speaking) {
            while (waiting.isNotEmpty()) sendResidual(link, waiting.removeFirst())
            duckRequested = -1
            last = reference
        }
        if (!speaking || reference == null) { link.send(samples, n, rate); return }
        waiting.addLast(Frame(samples.copyOf(n), rate, reference.weakVoice))
        if (candidate) {
            duckRequested = now; evidenceMs = 0.0; duck(true)
            val recent = waiting.map { RoomNoise.rms(it.data, it.data.size, it.rate) }.sorted()
            beforeDuck = recent[recent.size * 2 / 3]
        }
        val elapsed = if (duckRequested >= 0 && mutedAt >= duckRequested) now - mutedAt else -1
        if (elapsed >= (if (headphones) 120 else 155)) {
            val duration = n * 1000.0 / rate
            val continued = RoomNoise.rms(samples, n, rate) > maxOf(90.0, background * 1.75 + 18, beforeDuck * .42)
            evidenceMs = if (continued) evidenceMs + duration else maxOf(0.0, evidenceMs - duration / 2)
        }
        if (duckRequested >= 0 && evidenceMs >= 52) {
            interrupt()
            while (waiting.isNotEmpty()) {
                val voice = waiting.removeFirst()
                link.send(voice.data, voice.data.size, voice.rate)
            }
            duckRequested = -1
            return
        }
        // Judge the quiet interval from the actual mute. Routing and the main thread may delay it.
        val quietWindow = if (elapsed >= 0) elapsed else now - duckRequested
        if (duckRequested >= 0 && quietWindow < 330) return
        if (duckRequested >= 0) { duck(false); reference.listenAgain(); duckRequested = -1 }
        while (waiting.size > 6) sendResidual(link, waiting.removeFirst())
    }

    private fun sendResidual(link: LiveSpeechSession, frame: Frame) {
        val audio = if (frame.possibleVoice) frame.data else if (frame.data.size <= silence.size) silence else ShortArray(frame.data.size)
        link.send(audio, frame.data.size, frame.rate)
    }
}
