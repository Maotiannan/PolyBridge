package dev.polybridge.app

import android.content.Context
import android.content.ContextWrapper
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.*
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.max

/** Product AudioTrack, LiveDesk, routing and history. Known PCM at the microphone and cloud boundaries. */
@RunWith(AndroidJUnit4::class)
class LiveDuplexDeviceTest {
    @Test fun localPlaybackIsReleasedOnOverlapAndEchoDoesNotCreateATurn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testDuplex") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext.applicationContext
        val prefix = "qa-duplex-" + UUID.randomUUID()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefix + name, mode)
        }
        val store = ProbeStore(isolated)
        val settings = TranslationSettings(isolated)
        val clips = VoiceClips()
        lateinit var receiver: (LiveUpdate) -> Unit
        lateinit var desk: LiveDesk
        val latencies = mutableListOf<Long>()
        val session = object : LiveSpeechSession {
            override fun connect() = Unit
            override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
            override fun finish() = Unit
            override fun close() = Unit
        }
        fun waveform(name: String) = context.assets.open("phrasebook/$name.wav").use { stream ->
            val wave = stream.readBytes()
            wave.copyOfRange(44, wave.size)
        }
        fun sample(pcm: ByteArray, n: Int): Int {
            if (n < 0 || n * 2 + 1 >= pcm.size) return 0
            return (pcm[n * 2].toInt() and 255) or (pcm[n * 2 + 1].toInt() shl 8)
        }
        fun waitFor(ms: Long, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + ms
            while (!condition()) { if (SystemClock.elapsedRealtime() >= end) throw AssertionError("播放器超时 ${desk.view.value}"); SystemClock.sleep(5) }
        }
        val began = SystemClock.elapsedRealtime()
        val duration = args.getString("duplexDurationMs")?.toLongOrNull()?.coerceIn(0, 1_800_000) ?: 0
        val foreground = if (duration > 0) openProjectLauncher() else null
        val keep = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val alreadyLit = foreground?.window?.attributes?.flags?.and(keep) == keep
        desk = LiveDesk(isolated, store, settings, clips, connect = { receiver = it; session }, microphone = { microphone ->
            object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted()
                    var index = 0
                    val quiet = ShortArray(320)
                    do {
                        val side = Side.entries[index % 2]
                        val target = side.target.substringBefore('-')
                        val phrase = waveform("tickets-$target")
                        val far = phrase + phrase + phrase
                        val near = waveform("station-${side.source.substringBefore('-')}")
                        val offset = (0 until near.size / 2).first { kotlin.math.abs(sample(near, it)) > 600 }
                        val event = LiveUpdate.Finished("line-$index", side, 0, "fixture $index", "tickets $index", far)
                        receiver(event)
                        waitFor(6000) { desk.view.value.speaking }
                        val route = settings.lastRoutes.value.getValue(side.target)
                        assertTrue(route.gain > 0)
                        val start = SystemClock.elapsedRealtime() - route.positionMs
                        var energyIn = 0.0
                        var energyOut = 0.0
                        var overlapAt = 0L
                        var opening = 0.0
                        var output = 0.0
                        val trace = mutableListOf<String>()
                        for (t in 20..1380 step 20) {
                            val due = start + t - SystemClock.elapsedRealtime()
                            if (due > 0) SystemClock.sleep(due)
                            val direct = IntArray(320)
                            val frame = ShortArray(320) { j ->
                                val n = (t - 20) * 24 + j * 3 / 2
                                if (t >= 840) direct[j] = sample(near, n - 820 * 24 + offset)
                                val echo = .15 * sample(far, n - 85 * 24)
                                (echo + direct[j] * .8).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            if (t == 840) {
                                assertEquals(trace.joinToString(), index, desk.view.value.interruptions)
                                overlapAt = SystemClock.elapsedRealtime()
                            }
                            if (t < 840) frame.forEach { energyIn += it.toDouble() * it }
                            microphone(frame, frame.size, 16000)
                            if (t % 100 == 0) {
                                val observed = settings.lastRoutes.value[side.target]
                                trace += "$t:${observed?.positionMs}/${SystemClock.elapsedRealtime()-start}/${observed?.ducked}/${desk.view.value.interruptions}"
                            }
                            if (t < 840) frame.forEach { energyOut += it.toDouble() * it }
                            if (t in 840..980) for (j in frame.indices) {
                                opening += direct[j].toDouble() * direct[j] * .8
                                output += direct[j].toDouble() * frame[j]
                            }
                        }
                        assertTrue("overlap $index failed to stop playback: $trace", desk.view.value.playbackStoppedAt >= overlapAt && !desk.view.value.speaking)
                        val elapsed = desk.view.value.playbackStoppedAt - overlapAt
                        assertEquals(index + 1, desk.view.value.interruptions)
                        assertTrue("release latency $elapsed", elapsed in 0..500)
                        val leak = energyOut / max(1.0, energyIn)
                        assertTrue("echo leakage $leak", energyIn > 0 && leak < .05)
                        assertTrue("opening lost", output / max(1.0, opening) > .65)
                        receiver(event) // Duplicate provider completion can never restart its player.
                        assertEquals(minOf(100, index + 1), store.chat.value.turns.size)
                        assertNotNull(clips[clips.key(store.chat.value.turns.last(), settings.state.value.cloud)])
                        latencies += elapsed
                        if (index < 2) inst.sendStatus(0, Bundle().apply { putString("overlap", "$target releaseMs=$elapsed echoPowerFraction=$leak route=${route.type}") })
                        index++
                        if (duration > 0) repeat(100) { microphone(quiet, quiet.size, 16000); SystemClock.sleep(20) }
                    } while (index < 2 || SystemClock.elapsedRealtime() - began < duration)
                    request.end.set(End.RELEASED)
                    return request.empty(Verdict.SIGNAL)
                }
            }
        })
        try {
            if (foreground != null && !alreadyLit) inst.runOnMainSync { foreground.window.addFlags(keep) }
            val request = requireNotNull(store.begin(Kind.LISTEN, PlaybackTarget.SELF, null, null, null))
            val result = desk.record(request)
            store.finish(result)
            assertEquals(result.errorCode, Verdict.SIGNAL, result.verdict)
            val sorted = latencies.sorted()
            inst.sendStatus(0, Bundle().apply { putString("duplexRun", "turns=${sorted.size} elapsedMs=${SystemClock.elapsedRealtime()-began} p95Ms=${sorted[(sorted.size*95-1)/100]} maxMs=${sorted.last()}") })
        } finally {
            if (foreground != null && !alreadyLit) inst.runOnMainSync { foreground.window.clearFlags(keep) }
            listOf("diagnostics", "translator", "audio_roles").forEach { context.deleteSharedPreferences(prefix + it) }
        }
    }
}
