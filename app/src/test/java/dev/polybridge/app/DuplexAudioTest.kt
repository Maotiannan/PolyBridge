package dev.polybridge.app

import dev.polybridge.app.audio.PlaybackFloor
import dev.polybridge.core.LiveSpeechSession
import dev.polybridge.core.PlaybackEcho
import dev.polybridge.core.RoomNoise
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class DuplexAudioTest {
    private fun speech(name: String): ByteArray {
        val wave = File("src/main/assets/phrasebook/$name.wav").readBytes()
        return wave.copyOfRange(44, wave.size)
    }

    private fun sample(pcm: ByteArray, n: Int): Int {
        if (n < 0 || n * 2 + 1 >= pcm.size) return 0
        return (pcm[n * 2].toInt() and 255) or (pcm[n * 2 + 1].toInt() shl 8)
    }

    private data class Trial(val interrupts: List<Int>, val retained: Double, val leak: Double)

    private fun runTrial(reference: ByteArray, other: ByteArray?, delay: Int, gain: Double, rate: Int): Trial {
        val echo = PlaybackEcho()
        echo.start(reference)
        var inputEnergy = 0.0
        var leakEnergy = 0.0
        var expected = 0.0
        var projected = 0.0
        val interrupts = mutableListOf<Int>()
        val firstVoiced = other?.let { bytes -> (0 until bytes.size / 2).first { abs(sample(bytes, it)) > 600 } } ?: 0
        for (t in 0 until 1400 step 20) {
            if (t % 100 == 0) echo.position(t, t.toLong())
            val near = IntArray(rate / 50)
            val frames = ShortArray(rate / 50) { i ->
                val n = t * 24 + i * 24000 / rate
                val far = gain * (sample(reference, n - delay * 24) + .18 * sample(reference, n - (delay + 18) * 24))
                if (other != null && t >= 460) near[i] = (sample(other, n - 460 * 24 + firstVoiced) * .8).toInt()
                (far + near[i]).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (other == null) for (s in frames) inputEnergy += s.toDouble() * s
            if (echo.filter(frames, frames.size, rate, (t + 20).toLong())) interrupts += t + 20
            for (i in frames.indices) {
                if (other == null) leakEnergy += frames[i].toDouble() * frames[i]
                if (t in 460..600) { expected += near[i].toDouble() * near[i]; projected += near[i].toDouble() * frames[i] }
            }
        }
        return Trial(interrupts, projected / max(1.0, expected), leakEnergy / max(1.0, inputEnergy))
    }

    @Test fun delayedPlaybackAloneNeverTakesTheFloor() {
        for (lang in listOf("fr", "zh")) for (delay in listOf(35, 115, 215)) for (gain in listOf(.08, .38)) {
            val result = runTrial(speech("tickets-$lang"), null, delay, gain, 16000)
            assertTrue("false interrupt $lang $delay $gain $result", result.interrupts.isEmpty())
            assertTrue("echo leak $result", result.leak < .015)
        }
    }

    @Test fun backgroundHumAndAChangingEchoLevelDoNotSendThePlaybackBackToRecognition() {
        val pcm = speech("train-zh")
        val echo = PlaybackEcho(212.0).apply { start(pcm) }
        var original = 0.0
        var leaked = 0.0
        for (t in 0 until pcm.size / 48 + 150 step 20) {
            if (t % 100 == 0) echo.position(t, t.toLong())
            val far = IntArray(320) { i -> (sample(pcm, (t - 100) * 24 + i * 3 / 2) * (if (t < 700) .48 else .12)).toInt() }
            val input = ShortArray(320) { i -> (far[i] + 300 * sin((t * 16 + i) * 2 * PI * 140 / 16000)).toInt().toShort() }
            echo.filter(input, input.size, 16000, t + 20L)
            if (t < 700) continue
            for (i in far.indices) {
                original += far[i].toDouble() * far[i]
                if (echo.weakVoice) leaked += abs(input[i].toDouble() * far[i])
            }
        }
        val fraction = leaked / original
        println("background-hum correlated playback fraction=$fraction")
        assertTrue("correlated playback sent as speech=$fraction", fraction < .045)
    }

    @Test fun lowRoomNoiseUnderPlaybackIsKeptOutOfRecognitionAndNewSpeechSurvivesTheTail() {
        val noise = RoomNoise()
        val fan = ShortArray(320) { (sin(it * 2 * PI * 300 / 16000) * 420).toInt().toShort() }
        repeat(30) { noise.observe(fan, fan.size, 16000) }
        val echo = PlaybackEcho(noise.level())
        echo.start(speech("tickets-fr")); echo.position(0, 0)
        repeat(20) { n ->
            val input = fan.clone()
            assertFalse(echo.filter(input, input.size, 16000, (n + 1) * 20L))
            assertTrue("stationary room sound sent as speech", input.all { it == 0.toShort() })
        }
        echo.stop(400)
        val next = fan.clone()
        echo.filter(next, next.size, 16000, 800)
        assertArrayEquals(fan, next)
    }

    @Test fun anotherVoiceKeepsItsOpeningAndInterruptsOnceInBothDirections() {
        for (lang in listOf("fr", "zh")) for (rate in listOf(16000, 48000)) for (gain in listOf(0.0, .22)) {
            val other = if (lang == "fr") "zh" else "fr"
            val result = runTrial(speech("tickets-$lang"), speech("station-$other"), 115, gain, rate)
            assertEquals("double talk $lang at $rate $result", 1, result.interrupts.size)
            val latency = result.interrupts.single() - 460
            assertTrue("interrupt latency $latency", latency in 20..220)
            assertTrue("lost beginning $result", result.retained > .70)
            println("double-talk lang=$lang rate=$rate gain=$gain delayMs=$latency opening=${result.retained}")
        }
    }

    @Test fun interruptedSpeechSurvivesDelayedHardwareMuteConfirmation() {
        val echo = PlaybackEcho().apply { start(speech("tickets-fr")); position(0, 0) }
        val floor = PlaybackFloor()
        val session = object : LiveSpeechSession {
            override fun connect() = Unit
            override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
            override fun finish() = Unit
            override fun close() = Unit
        }
        var requested = -1L
        var muted = -1L
        var interrupted = -1L
        for (now in 20L..540L step 20) {
            if (requested >= 0 && now >= requested + 160 && muted < 0) muted = now
            val frame = ShortArray(320) { (2400 * sin(it * 2 * PI * 175 / 16000)).toInt().toShort() }
            floor.send(frame, frame.size, 16000, now, echo, true, false, 0.0, muted, session,
                duck = { if (it) requested = now else { requested = -1; muted = -1 } }, interrupt = { interrupted = now })
            if (interrupted >= 0) break
        }
        assertTrue("active speech did not take the floor after the output muted", interrupted in 300..500)
    }
}
