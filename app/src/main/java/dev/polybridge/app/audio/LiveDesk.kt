package dev.polybridge.app.audio

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.polybridge.app.*
import dev.polybridge.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ListeningState(val id: String? = null, val ready: Boolean = false,
    val partial: LiveUpdate.Caption? = null, val turnIds: List<String> = emptyList(), val speaking: Boolean = false,
    val isolatedOutput: Boolean = false, val issue: TranslationIssue? = null, val reconnecting: Boolean = false,
    val interruptions: Int = 0, val playbackStoppedAt: Long = 0, val lastLine: LiveUpdate.Caption? = null, val kind: Kind? = null)

internal typealias MicFrames = (ShortArray, Int, Int) -> Unit

internal class LiveDesk(private val context: Context, private val store: ProbeStore, private val settings: TranslationSettings,
    private val clips: VoiceClips,
    private val connect: ((LiveUpdate) -> Unit) -> LiveSpeechSession = {
        settings.provider()?.live(it) ?: throw TranslationFailure(TranslationIssue.CREDENTIAL)
    }, private val microphone: (MicFrames) -> AudioInputSource = { AndroidAudioInput(context, onFrame = it) },
    private val captionConnection: ((LiveUpdate) -> Unit) -> LiveSpeechSession = {
        settings.provider()?.live(true, it) ?: throw TranslationFailure(TranslationIssue.CREDENTIAL)
    }) {
    private val main = Handler(Looper.getMainLooper())
    private val routing = PlaybackRouting(context, settings)
    private val state = MutableStateFlow(ListeningState())
    val view = state.asStateFlow()
    private var session: LiveSpeechSession? = null
    private var player: PcmSpeechPlayer? = null
    @Volatile private var echo: PlaybackEcho? = null
    private var voiceEpoch = 0L
    @Volatile private var background = RoomNoise()
    @Volatile private var mutedAt = -1L
    @Volatile private var roomLevel = 0.0
    private val inputUs = java.util.concurrent.atomic.AtomicLong()
    private val spoken = SpokenEchoes()

    fun record(request: ProbeRequest, projection: android.media.projection.MediaProjection? = null): ProbeEntry {
        synchronized(this) {
            state.value = ListeningState(id = request.id, kind = request.kind); background = RoomNoise(); echo = null
            spoken.reset(); inputUs.set(0)
        }
        var live: LiveSpeechSession? = null
        return try {
            val captions = request.kind == Kind.CAPTIONS
            val connection = (if (captions) captionConnection else connect) { event -> receive(request, event) }
            live = connection
            synchronized(this) { session = connection }
            if (request.end.get() != null) return request.empty(Verdict.CANCELLED)
            connection.connect()
            if (request.end.get() != null) return request.empty(Verdict.CANCELLED)
            val floor = PlaybackFloor()
            val meter = InputLevel()
            var publishedAt = -100L
            val input = microphone inputFrame@{ samples, n, rate ->
                val now = SystemClock.elapsedRealtime()
                val level = meter.read(samples, n)
                if (now - publishedAt >= 65) { request.level.value = level; publishedAt = now }
                inputUs.addAndGet(n * 1_000_000L / rate)
                if (captions) { connection.send(samples, n, rate); return@inputFrame }
                if (!state.value.speaking && (state.value.playbackStoppedAt == 0L ||
                        SystemClock.elapsedRealtime() - state.value.playbackStoppedAt > 350)) background.observe(samples, n, rate)
                val current = echo
                floor.send(samples, n, rate, now, current, state.value.speaking, state.value.isolatedOutput, roomLevel, mutedAt,
                    connection, duck = { enabled -> main.post { synchronized(this) { if (echo === current) {
                        player?.duck(enabled); mutedAt = if (enabled) SystemClock.elapsedRealtime() else -1L
                    } } } }, interrupt = {
                    synchronized(this) { if (echo === current) {
                        state.value = state.value.copy(interruptions = state.value.interruptions + 1)
                        interrupt()
                    } }
                })
            }
            val measured = input.measure(request, projection) {
                request.sampling.value = true
                synchronized(this) { state.value = state.value.copy(ready = true) }
            }
            if (request.end.get() == End.RELEASED) connection.finish()
            measured
        } catch (failure: TranslationFailure) {
            synchronized(this) { if (request.end.get() != End.CANCELLED) state.value = state.value.copy(issue = failure.issue) }
            request.empty(Verdict.FAILED, "LIVE_" + failure.issue.name)
        } catch (_: Exception) {
            synchronized(this) { state.value = state.value.copy(issue = TranslationIssue.SERVICE) }
            request.empty(Verdict.FAILED, "LIVE_SERVICE")
        } finally {
            request.level.value = 0f
            live?.close()
            synchronized(this) {
                session = null
                val partial = state.value.partial
                if (partial != null && partial.source.isNotBlank() && state.value.issue != null) {
                    val id = "live-" + request.id + "-" + partial.id
                    store.changeChat { it.typed(id, partial.side, partial.source).failed(id, state.value.issue!!) }
                }
                state.value = state.value.copy(id = null, ready = false, partial = null)
            }
            interrupt()
        }
    }

    @Synchronized private fun receive(request: ProbeRequest, event: LiveUpdate) {
        if (state.value.id != request.id || request.end.get()?.let { it != End.RELEASED } == true) return
        when (event) {
            is LiveUpdate.Reconnecting -> {
                state.value.partial?.takeIf { it.source.isNotBlank() }?.let { line ->
                    val id = "live-" + request.id + "-" + line.id
                    store.changeChat { it.typed(id, line.side, line.source).failed(id, TranslationIssue.NETWORK) }
                    state.value = state.value.copy(turnIds = (state.value.turnIds + id).distinct().takeLast(100))
                }
                interrupt()
                state.value = state.value.copy(partial = null, ready = false, reconnecting = true)
            }
            LiveUpdate.Reconnected -> {
                spoken.reset(); inputUs.set(0)
                state.value = state.value.copy(ready = true, reconnecting = false)
            }
            is LiveUpdate.Caption -> {
                if (state.value.speaking && spoken.independent(event.side, event.source, event.startedMs)) {
                    state.value = state.value.copy(interruptions = state.value.interruptions + 1)
                    interrupt()
                }
                state.value = state.value.copy(partial = event)
            }
            is LiveUpdate.Finished -> {
                if (spoken.repeated(event.side, event.source, event.startedMs, inputUs.get() / 1000)) {
                    state.value = state.value.copy(partial = state.value.partial?.takeUnless { it.id == event.id })
                    return
                }
                val id = "live-" + request.id + "-" + event.id
                val added = store.changeChat { chat ->
                    if (chat.turns.any { it.id == id }) chat
                    else chat.typed(id, event.side, event.source).translated(id, event.source, event.target)
                }
                if (added) {
                    state.value = state.value.copy(partial = state.value.partial?.takeUnless { it.id == event.id },
                        turnIds = (state.value.turnIds + id).takeLast(100),
                        lastLine = LiveUpdate.Caption(event.id, event.side, event.source, event.target, event.startedMs))
                    if (event.pcm.isNotEmpty() && request.kind != Kind.CAPTIONS) {
                        val wave = waveFromPcm(24000, event.pcm)
                        val turn = store.chat.value.turns.firstOrNull { it.id == id } ?: return
                        clips.put(clips.key(turn, settings.state.value.cloud), wave)
                        if (request.end.get() == null && settings.state.value.autoSpeak && state.value.partial == null) {
                            val epoch = ++voiceEpoch
                            main.post { if (currentVoice(request.id, epoch)) play(event.pcm, event.side.target, event.target, epoch) }
                        }
                    }
                }
            }
            is LiveUpdate.Stopped -> {
                state.value = state.value.copy(issue = event.issue)
                request.end.compareAndSet(null, End.INTERRUPTED)
            }
        }
    }

    @Synchronized private fun currentVoice(id: String, epoch: Long) = state.value.id == id && voiceEpoch == epoch

    fun stopNow(request: ProbeRequest) {
        val link = synchronized(this) {
            if (state.value.id != request.id) return
            request.end.compareAndSet(null, End.CANCELLED)
            session
        }
        link?.close()
        interrupt()
    }

    @Synchronized fun interrupt() {
        val epoch = ++voiceEpoch
        echo?.stop(SystemClock.elapsedRealtime())
        spoken.finish(inputUs.get() / 1000)
        state.value = state.value.copy(speaking = false, isolatedOutput = false)
        main.post { synchronized(this) { if (voiceEpoch == epoch && player != null) {
            player?.close(); player = null
            state.value = state.value.copy(playbackStoppedAt = SystemClock.elapsedRealtime())
        } } }
    }

    private fun play(pcm: ByteArray, language: String, text: String, epoch: Long) {
        player?.close()
        val media = PcmSpeechPlayer(routing)
        roomLevel = background.level()
        mutedAt = -1
        val reference = PlaybackEcho(roomLevel).apply { start(pcm) }
        echo = reference
        player = media
        var began = false
        fun finished() {
            synchronized(this) {
                if (epoch != voiceEpoch) return
                interrupt()
            }
        }
        fun routed(output: TranslationSettings.PlaybackObservation) {
            synchronized(this) {
                if (voiceEpoch != epoch) return
                val speaking = (output.gain > 0 || output.ducked) && output.positionMs > 0
                if (speaking && !began) {
                    began = true
                    spoken.start(language, text, inputUs.get() / 1000)
                }
                if (speaking) reference.position(output.positionMs, SystemClock.elapsedRealtime())
                state.value = state.value.copy(speaking = speaking, isolatedOutput = headphoneType(output.type))
            }
        }
        try {
            media.play(VoicePcm(pcm, 0, pcm.size, 24000), language, false, ::routed) { finished() }
        } catch (_: Exception) { finished() }
    }
}
