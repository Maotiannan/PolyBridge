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
    val drafts: List<LiveUpdate.Caption> = emptyList(), val turnIds: List<String> = emptyList(), val speaking: Boolean = false,
    val isolatedOutput: Boolean = false, val issue: TranslationIssue? = null, val reconnecting: Boolean = false,
    val interruptions: Int = 0, val playbackStoppedAt: Long = 0, val lastLine: LiveUpdate.Caption? = null,
    val kind: Kind? = null, val captureId: String? = null) {
    val partial get() = drafts.lastOrNull()
}

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
    val chat = store.chat
    private var session: LiveSpeechSession? = null
    private var player: PcmSpeechPlayer? = null
    @Volatile private var echo: PlaybackEcho? = null
    private var voiceEpoch = 0L
    @Volatile private var background = RoomNoise()
    @Volatile private var mutedAt = -1L
    @Volatile private var roomLevel = 0.0
    private val inputUs = java.util.concurrent.atomic.AtomicLong()
    private val spoken = SpokenEchoes()
    private var epoch = 0
    private var offsetMs = 0L

    fun record(request: ProbeRequest, projection: android.media.projection.MediaProjection? = null): ProbeEntry {
        synchronized(this) {
            state.value = ListeningState(id = request.id, kind = request.kind, captureId = request.id); background = RoomNoise(); echo = null
            epoch = 0; offsetMs = 0
            spoken.reset(); inputUs.set(0)
        }
        var live: LiveSpeechSession? = null
        return try {
            val captions = request.kind == Kind.CAPTIONS
            val transport = (if (captions) captionConnection else connect) { event -> receive(request, event) }
            live = transport
            val connection = SpeechGate(context, transport)
            live = connection
            synchronized(this) { session = transport }
            if (request.end.get() != null) return request.empty(Verdict.CANCELLED)
            connection.connect()
            if (request.end.get() != null) return request.empty(Verdict.CANCELLED)
            val floor = PlaybackFloor(connection::recheckOpening)
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
            if (request.end.get() == End.RELEASED || request.end.get() == null && measured.verdict == Verdict.SIGNAL) connection.finish()
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
                preserveDrafts(request, state.value.issue ?: TranslationIssue.INTERRUPTED)
                state.value = state.value.copy(id = null, ready = false)
            }
            interrupt()
        }
    }

    private fun localId(request: ProbeRequest, side: Side, id: String) = "live-${request.id}-$epoch-${side.name}-$id"

    private fun preserveDrafts(request: ProbeRequest, reason: TranslationIssue) {
        val drafts = state.value.drafts
        if (drafts.isEmpty()) return
        store.changeChat { chat -> drafts.fold(chat) { saved, line ->
            saved.heard(line, LiveOrigin(request.id, request.kind, line.startedMs), reason)
        } }
        state.value = state.value.copy(drafts = emptyList(), turnIds = (state.value.turnIds + drafts.map { it.id }).distinct())
    }

    @Synchronized private fun receive(request: ProbeRequest, event: LiveUpdate) {
        if (state.value.id != request.id || request.end.get()?.let { it != End.RELEASED } == true) return
        when (event) {
            is LiveUpdate.Reconnecting -> {
                preserveDrafts(request, TranslationIssue.NETWORK)
                interrupt()
                state.value = state.value.copy(ready = false, reconnecting = true)
            }
            LiveUpdate.Reconnected -> {
                spoken.reset()
                offsetMs = maxOf(offsetMs + inputUs.getAndSet(0) / 1000, (state.value.lastLine?.startedMs ?: 0) + 1)
                epoch++
                state.value = state.value.copy(ready = true, reconnecting = false)
            }
            is LiveUpdate.Caption -> {
                val line = event.copy(id = localId(request, event.side, event.id), startedMs = offsetMs + event.startedMs)
                if (line.id in state.value.turnIds) return
                if (state.value.speaking && spoken.independent(event.side, event.source, event.startedMs)) {
                    state.value = state.value.copy(interruptions = state.value.interruptions + 1)
                    interrupt()
                }
                val pending = state.value.drafts.toMutableList()
                val index = pending.indexOfFirst { it.id == line.id }
                if (index < 0) pending.add(line) else pending[index] = line
                state.value = state.value.copy(drafts = pending.sortedBy { it.startedMs })
            }
            is LiveUpdate.Withdrawn -> {
                val id = localId(request, event.side, event.id)
                state.value = state.value.copy(drafts = state.value.drafts.filterNot { it.id == id })
            }
            is LiveUpdate.Finished -> {
                val id = localId(request, event.side, event.id)
                val drafts = state.value.drafts.filterNot { it.id == id }
                if (spoken.repeated(event.side, event.source, event.startedMs, inputUs.get() / 1000)) {
                    state.value = state.value.copy(drafts = drafts)
                    return
                }
                val line = LiveUpdate.Caption(id, event.side, event.source, event.target, offsetMs + event.startedMs)
                val added = store.changeChat { it.heard(line, LiveOrigin(request.id, request.kind, line.startedMs), event.issue) }
                if (added) {
                    val latest = state.value.lastLine
                    state.value = state.value.copy(drafts = drafts, turnIds = state.value.turnIds + id,
                        lastLine = if (latest != null && latest.startedMs > line.startedMs) latest else line)
                    if (event.issue == null && event.pcm.isNotEmpty() && request.kind != Kind.CAPTIONS) {
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
