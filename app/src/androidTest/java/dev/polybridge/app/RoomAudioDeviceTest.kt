package dev.polybridge.app

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Both sounds travel through the room to the Fold5 microphone. Only anonymous levels leave the test. */
@RunWith(AndroidJUnit4::class)
class RoomAudioDeviceTest {
    @Test fun speakerEchoAndAnIndependentSentenceReachTheActualMicrophone() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roomAudio") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = openProjectLauncher().application as PolyBridgeApp
        val tag = "qa-room-" + UUID.randomUUID()
        val ctx = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(tag + name, mode)
        }
        val settings = TranslationSettings(ctx)
        val store = ProbeStore(ctx)
        val microphone = availablePorts(ctx).first { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val request = requireNotNull(store.begin(Kind.LISTEN, PlaybackTarget.SELF, microphone.id, null, null))
        val phase = AtomicInteger(0)
        val raw = Array(3) { SignalMeter() }
        val sent = Array(3) { SignalMeter() }
        val dipped = SignalMeter()
        val firstQuiet = AtomicLong(0)
        lateinit var feed: (LiveUpdate) -> Unit
        val desk = LiveDesk(ctx, store, settings, VoiceClips(), connect = { receive ->
            feed = receive
            object : LiveSpeechSession {
                override fun connect() = Unit
                override fun send(samples: ShortArray, length: Int, rate: Int) { sent[phase.get()].add(samples, length) }
                override fun finish() = Unit
                override fun close() = Unit
            }
        }, microphone = { push ->
            AndroidAudioInput(ctx, onFrame = { data, count, rate ->
                if (phase.get() == 1 && settings.lastRoutes.value["fr-FR"]?.ducked == true) dipped.add(data, count)
                if (phase.get() == 2 && settings.lastRoutes.value["fr-FR"]?.ducked == true)
                    firstQuiet.compareAndSet(0, SystemClock.elapsedRealtime())
                raw[phase.get()].add(data, count); push(data, count, rate)
            })
        })
        fun pcm(asset: String): ByteArray = app.assets.open("phrasebook/$asset.wav").use {
            val wave = it.readBytes(); wave.copyOfRange(44, wave.size)
        }
        fun waitFor(ms: Long, message: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + ms
            while (!condition()) { check(SystemClock.elapsedRealtime() < until) { message }; SystemClock.sleep(8) }
        }
        val executor = Executors.newSingleThreadExecutor()
        val recording = executor.submit<ProbeEntry> { desk.record(request) }
        var independent: PcmSpeechPlayer? = null
        var onset = 0L
        var early = 0
        try {
            waitFor(5000, "机身麦克风未打开") { request.sampling.value }
            SystemClock.sleep(1600)
            val sentence = pcm("tickets-fr")
            phase.set(1)
            feed(LiveUpdate.Finished("spoken", Side.CHINESE, 0, "两张票", "Deux billets", sentence + sentence + sentence))
            waitFor(4000, "本机扬声器未播放测试译音") { desk.view.value.speaking }
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, settings.lastRoutes.value["fr-FR"]?.type)
            SystemClock.sleep(1250)
            early = desk.view.value.interruptions
            val external = InstrumentationRegistry.getArguments().getString("externalTrial")
            if (external != null) {
                require(external.matches(Regex("[0-9a-f]{8}")))
                onset = SystemClock.elapsedRealtime() + 1000
                android.util.Log.i("PolyBridgeRoomQA", "$external playAt=$onset")
                SystemClock.sleep((onset - SystemClock.elapsedRealtime()).coerceAtLeast(0)); phase.set(2)
            } else {
                val voice = pcm("station-zh")
                var i = 0
                while (i + 1 < voice.size && abs(((voice[i].toInt() and 255) or (voice[i + 1].toInt() shl 8))) < 650) i += 2
                val spoken = VoicePcm(voice, i, voice.size - i, 24000)
                inst.runOnMainSync {
                    val media = PcmSpeechPlayer(PlaybackRouting(ctx, TranslationSettings(ctx)))
                    independent = media
                    media.play(spoken, "fr-FR", false, { output ->
                        if (onset == 0L && output.positionMs > 0 && output.gain > 0) {
                            onset = SystemClock.elapsedRealtime() - output.positionMs
                            phase.set(2)
                        }
                    }) {}
                }
            }
            waitFor(5000, "独立声源没有输出已知语音帧") { phase.get() == 2 }
            SystemClock.sleep(1300)
        } finally {
            inst.runOnMainSync { independent?.close(); independent = null }
            request.end.compareAndSet(null, End.RELEASED)
            val measured = recording.get(8, TimeUnit.SECONDS)
            store.finish(measured)
            executor.shutdownNow()
            val state = desk.view.value
            val latency = if (onset > 0 && state.playbackStoppedAt >= onset) state.playbackStoppedAt - onset else -1
            val levels = (0..2).joinToString(" / ") { "${raw[it].snapshot().rmsDbfs}→${sent[it].snapshot().rmsDbfs}" }
            val quiet = if (firstQuiet.get() >= onset && onset > 0) firstQuiet.get() - onset else -1
            inst.sendStatus(0, Bundle().apply { putString("roomAudio", "input=${measured.inputRoute?.type} early=$early interrupts=${state.interruptions} quietMs=$quiet releaseMs=$latency rmsDbfs=$levels duckedDbfs=${dipped.snapshot().rmsDbfs}") })
            listOf("diagnostics", "translator", "audio_roles").forEach { app.deleteSharedPreferences(tag + it) }
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, measured.inputRoute?.type)
        }
        assertEquals("朗读在另一句开始前被误打断", 0, early)
        assertEquals("独立中文声音未打断法语朗读", 1, desk.view.value.interruptions)
        assertTrue("扬声器未在 500 毫秒内静音", firstQuiet.get() - onset in 0..500)
        assertTrue("朗读未在本句内释放", desk.view.value.playbackStoppedAt >= firstQuiet.get())
    }
}
