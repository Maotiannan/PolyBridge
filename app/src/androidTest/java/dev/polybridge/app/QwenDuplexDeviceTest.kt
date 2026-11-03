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
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class QwenDuplexDeviceTest {
    /** The actual provider sees only bundled sentences mixed with the audio it just returned. */
    @Test fun aFrenchInterruptionMakesExactlyTwoBilingualTurns() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudDuplex") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = openProjectLauncher().application as PolyBridgeApp
        val prefix = "qa-qwen-duplex-" + UUID.randomUUID()
        val ctx = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        val store = ProbeStore(ctx)
        val settings = TranslationSettings(ctx)
        val soft = InstrumentationRegistry.getArguments().getString("softDuplex") == "true"
        val clips = VoiceClips()
        lateinit var desk: LiveDesk
        var release = -1L
        fun sample(pcm: ByteArray, n: Int): Int = if (n < 0 || 2 * n + 1 >= pcm.size) 0 else
            (pcm[n * 2].toInt() and 255) or (pcm[n * 2 + 1].toInt() shl 8)
        fun asset(name: String) = app.assets.open("phrasebook/$name.wav").use { val w = it.readBytes(); w.copyOfRange(44, w.size) }
        desk = LiveDesk(ctx, store, settings, clips, connect = { requireNotNull(app.settings.provider()).live(it) }, microphone = { push ->
            object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted()
                    var clock = 0L
                    fun frame(block: (Int) -> Int) {
                        val samples = ShortArray(320) {
                            val hum = if (soft) (300 * kotlin.math.sin((clock + it) * 2 * Math.PI * 140 / 16000)).toInt() else 0
                            (block(it) + hum).coerceIn(-32768, 32767).toShort()
                        }
                        clock += samples.size
                        push(samples, samples.size, 16000)
                        SystemClock.sleep(20)
                    }
                    fun waitForTranslation(count: Int): Turn {
                        val until = SystemClock.elapsedRealtime() + 35000
                        while (store.chat.value.turns.size < count || !desk.view.value.speaking) {
                            if (SystemClock.elapsedRealtime() > until) throw AssertionError("千问未返回第 $count 条译音，已收 ${store.chat.value.turns.size} 条")
                            frame { 0 }
                        }
                        return store.chat.value.turns[count - 1]
                    }
                    fun clip(turn: Turn): ByteArray {
                        val wave = requireNotNull(clips[clips.key(turn, settings.state.value.cloud)])
                        return wave.copyOfRange(44, wave.size)
                    }
                    val tickets = asset("tickets-zh")
                    for (pos in 0 until tickets.size / 2 step 480) frame { sample(tickets, pos + it * 3 / 2) }
                    val first = waitForTranslation(1)
                    assertTrue(first.side == Side.CHINESE && first.sourceText.contains("票") && first.translatedText.contains("billet", true))
                    val far = clip(first)
                    val other = asset("train-fr")
                    val voiced = (0 until other.size / 2).first { abs(sample(other, it)) > 600 }
                    val start = SystemClock.elapsedRealtime() - settings.lastRoutes.value.getValue(first.side.target).positionMs
                    val onset = SystemClock.elapsedRealtime() + 380
                    assertTrue("第一句译音太短", far.size / 48 > onset - start + 180)
                    var nextSample = voiced
                    while (nextSample < other.size / 2 + 8640) {
                        val now = SystemClock.elapsedRealtime()
                        val stopped = desk.view.value.playbackStoppedAt
                        val fromFar = ((now - start - 85) * 24).toInt()
                        frame { i ->
                            val near = if (now >= onset) sample(other, nextSample + i * 3 / 2) else 0
                            val echo = if (stopped == 0L || now < stopped + 85) sample(far, fromFar + i * 3 / 2) else 0
                            (near * (if (soft) .14 else .8) + echo * .16).toInt()
                        }
                        if (now >= onset) nextSample += 480
                    }
                    release = desk.view.value.playbackStoppedAt - onset
                    if (!soft) assertTrue("第一句停止耗时 $release", release in 0..500)
                    val second = waitForTranslation(2)
                    assertTrue("受控第二句 ${second.side} ${second.sourceText} / ${second.translatedText}",
                        second.side == Side.FRENCH && second.sourceText.contains("train", true) && second.translatedText.any { it in "车班" })
                    val echo = clip(second)
                    val secondStart = SystemClock.elapsedRealtime() - settings.lastRoutes.value.getValue(second.side.target).positionMs
                    val levels = ArrayDeque<Pair<Long, Boolean>>()
                    while (SystemClock.elapsedRealtime() - secondStart < echo.size / 48 + 1800) {
                        val now = SystemClock.elapsedRealtime()
                        levels.addLast(now to (desk.view.value.speaking && settings.lastRoutes.value.getValue(second.side.target).gain > 0))
                        while (levels.size > 1 && levels[1].first <= now - 100) levels.removeFirst()
                        val pos = ((now - secondStart - 100) * 24).toInt()
                        frame { if (levels.first().second) (sample(echo, pos + it * 3 / 2) * .15).toInt() else 0 }
                    }
                    request.end.set(End.RELEASED)
                    return request.empty(Verdict.SIGNAL)
                }
            }
        })
        try {
            val request = requireNotNull(store.begin(Kind.LISTEN, PlaybackTarget.SELF, null, null, null))
            val entry = desk.record(request)
            store.finish(entry)
            assertEquals(entry.errorCode, Verdict.SIGNAL, entry.verdict)
            assertEquals(store.chat.value.turns.joinToString(" | ") { "${it.sourceText} → ${it.translatedText}" }, 2, store.chat.value.turns.size)
            if (!soft) assertEquals(1, desk.view.value.interruptions)
            inst.sendStatus(0, Bundle().apply { putString("cloudDuplex", "two committed turns, interruptions=${desk.view.value.interruptions}, releaseMs=$release, weak=$soft, no echo turn") })
        } finally {
            listOf("diagnostics", "translator", "audio_roles").forEach { app.deleteSharedPreferences(prefix + it) }
        }
    }
}
