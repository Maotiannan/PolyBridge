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
import java.util.concurrent.atomic.AtomicBoolean

internal data class ListeningState(val id: String? = null, val ready: Boolean = false,
    val partial: LiveUpdate.Caption? = null, val turnIds: List<String> = emptyList(), val speaking: Boolean = false,
    val isolatedOutput: Boolean = false, val issue: TranslationIssue? = null, val reconnecting: Boolean = false)

internal class LiveDesk(private val context: Context, private val store: ProbeStore, private val settings: TranslationSettings) {
    private val main = Handler(Looper.getMainLooper())
    private val routing = PlaybackRouting(context, settings)
    private val state = MutableStateFlow(ListeningState())
    val view = state.asStateFlow()
    private var session: LiveSpeechSession? = null
    private var player: MediaPlayer? = null
    private val guard = AtomicBoolean(false)
    @Volatile private var quietUntil = 0L
    private var voiceEpoch = 0L

    fun record(request: ProbeRequest): ProbeEntry {
        synchronized(this) { state.value = ListeningState(id = request.id) }
        var live: LiveSpeechSession? = null
        return try {
            val api = settings.provider() ?: throw TranslationFailure(TranslationIssue.CREDENTIAL)
            val connection = api.live { event -> receive(request, event) }
            live = connection
            synchronized(this) { session = connection }
            if (request.end.get() != null) return request.empty(Verdict.CANCELLED)
            connection.connect()
            if (request.end.get() != null) return request.empty(Verdict.CANCELLED)
            val silence = ShortArray(960)
            val input = AndroidAudioInput(context, onFrame = { samples, n, rate ->
                val suppress = guard.get() || SystemClock.elapsedRealtime() < quietUntil
                connection.send(if (suppress) silence else samples, n, rate)
            })
            val measured = input.measure(request, null) {
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
        if (state.value.id != request.id || request.end.get() == End.CANCELLED) return
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
            LiveUpdate.Reconnected -> state.value = state.value.copy(ready = true, reconnecting = false)
            is LiveUpdate.Caption -> {
                if (state.value.speaking && state.value.isolatedOutput && state.value.partial?.id != event.id) interrupt()
                state.value = state.value.copy(partial = event)
            }
            is LiveUpdate.Finished -> {
                val id = "live-" + request.id + "-" + event.id
                val added = store.changeChat { chat ->
                    if (chat.turns.any { it.id == id }) chat
                    else chat.typed(id, event.side, event.source).translated(id, event.source, event.target)
                }
                if (added) {
                    state.value = state.value.copy(partial = state.value.partial?.takeUnless { it.id == event.id },
                        turnIds = (state.value.turnIds + id).takeLast(100))
                    if (request.end.get() == null && settings.state.value.autoSpeak && event.pcm.isNotEmpty()) {
                        val epoch = ++voiceEpoch
                        guard.set(true)
                        main.post { if (currentVoice(request.id, epoch)) play(event.pcm, event.side.target, epoch) }
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
        guard.set(false)
        quietUntil = SystemClock.elapsedRealtime() + 250
        state.value = state.value.copy(speaking = false, isolatedOutput = false)
        main.post { synchronized(this) { if (voiceEpoch == epoch) { player?.release(); player = null } } }
    }

    private fun play(pcm: ByteArray, language: String, epoch: Long) {
        player?.release()
        val media = MediaPlayer()
        player = media
        fun finished() {
            synchronized(this) {
                if (epoch != voiceEpoch) return
                interrupt()
            }
        }
        fun routed(device: AudioDeviceInfo?) {
            synchronized(this) {
                if (voiceEpoch != epoch) return
                val isolated = headphoneType(device?.type)
                guard.set(!isolated)
                state.value = state.value.copy(speaking = true, isolatedOutput = isolated)
            }
        }
        try {
            val wave = waveFromPcm(24000, pcm)
            val start = routing.prepare(media, language, ::routed)
            media.setDataSource(VoiceData(wave))
            media.setOnPreparedListener { if (synchronized(this) { voiceEpoch == epoch }) start() }
            media.setOnCompletionListener { finished() }
            media.setOnErrorListener { _, _, _ -> finished(); true }
            media.prepareAsync()
        } catch (_: Exception) { finished() }
    }
}
