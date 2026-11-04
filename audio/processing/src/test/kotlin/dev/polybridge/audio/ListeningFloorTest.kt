package dev.polybridge.audio

import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class ListeningFloorTest {
    @Test fun enhancedPipelineDoesNotTurnDelayedQuietPlaybackIntoSpeech() {
        for (name in listOf("tickets-fr", "train-zh")) for (delay in listOf(35, 100, 215)) for (rate in listOf(16000, 48000)) {
            val reference = asset("phrasebook/$name.wav").drop(44).toByteArray()
            val far = pcm(File(root, "app/src/main/assets/phrasebook/$name.wav"))
            val echo = PlaybackEcho(212.0).apply { start(reference); position(0, 0) }
            val sink = Sink()
            var ducks = 0; var interrupts = 0; var muted = -1L; var evidenceFrames = 0; var weakFrames = 0
            val duration = (far.size / 16 + delay + 500 + 19) / 20 * 20
            gate(sink, true).use { gate ->
                val floor = PlaybackFloor(gate::recheckOpening, { samples, n, hz ->
                    if (echo.weakVoice) weakFrames++
                    gate.voiceBeforeDuck(samples, n, hz).also { if (it) evidenceFrames++ }
                }, gate::resetVoiceBeforeDuck)
                for (time in 0 until duration step 20) {
                    if (time % 100 == 0) echo.position(time, time.toLong())
                    val frame = ShortArray(rate / 50) { i ->
                        val at = time * 16 + i * 16000 / rate
                        ((far.getOrNull(at - delay * 16) ?: 0) * .15 + 300 * sin(at * 2 * PI * 140 / 16000)).roundToInt().toShort()
                    }
                    floor.send(frame, frame.size, rate, time + 20L, echo, true, false, 212.0, muted, gate,
                        { if (it) ducks++; muted = if (it) time + 20L else -1L }, { interrupts++ })
                }
                floor.send(ShortArray(rate / 50), rate / 50, rate, duration + 20L, echo, false, false, 212.0, muted, gate, {}, {})
                gate.finish()
            }
            println("echoOnly=$name delay=$delay rate=$rate ducks=$ducks interrupts=$interrupts nonzero=${sink.samples.count { it != 0.toShort() }} evidenceFrames=$evidenceFrames weakFrames=$weakFrames")
            assertEquals(0, ducks); assertEquals(0, interrupts)
            assertTrue("playback went back to recognition", sink.samples.all { it == 0.toShort() })
            assertEquals((duration + 20) * 16, sink.samples.size)
        }
    }
    @Test fun enhancedPipelineKeepsQuietAndNormalInterruptionsInBothLanguagesAndRates() {
        val rain = pcm(File(root, "app/src/androidTest/assets/rain/1-21189-A-10.wav"))
        for (language in listOf("fr", "zh")) for (scale in listOf(.14, .8)) for (rate in listOf(16000, 48000)) for (rainFirst in listOf(false, true)) {
            val reference = asset("phrasebook/tickets-${if (language == "fr") "zh" else "fr"}.wav").drop(44).toByteArray()
            val far = pcm(File(root, "app/src/main/assets/phrasebook/tickets-${if (language == "fr") "zh" else "fr"}.wav"))
            val near = pcm(File(root, "app/src/main/assets/phrasebook/train-$language.wav"))
            val voiced = near.indexOfFirst { abs(it.toInt()) > 600 }
            val sink = Sink()
            val echo = PlaybackEcho(212.0).apply { start(reference); position(0, 0) }
            var muted = -1L; var playing = true; var interrupts = 0; var duckAt = -1L
            gate(sink, true).use { gate ->
                val floor = PlaybackFloor(gate::recheckOpening, gate::voiceBeforeDuck, gate::resetVoiceBeforeDuck)
                for (time in 0 until 2400 step 20) {
                    if (playing && muted < 0) echo.position(time, time.toLong())
                    val frame = ShortArray(rate / 50) { i ->
                        val at = time * 16 + i * 16000 / rate
                        val voice = if (time >= 460) (near.getOrNull(at - 460 * 16 + voiced) ?: 0) * scale else 0.0
                        val playback = if (playing && (muted < 0 || time < muted + 100)) (far.getOrNull(at - 1600) ?: 0) * .38 else 0.0
                        val background = if (rainFirst && time < 460) rain[at % rain.size].toDouble() else 300 * sin(at * 2 * PI * 140 / 16000)
                        (voice + playback + background).roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                    floor.send(frame, frame.size, rate, time + 20L, echo, playing, false, 212.0, muted, gate,
                        duck = { muted = if (it) time + 20L else -1L; if (it && duckAt < 0) duckAt = time + 20L },
                        interrupt = { interrupts++; playing = false; echo.stop(time + 20L) })
                }
                floor.send(ShortArray(rate / 50), rate / 50, rate, 2420, echo, false, false, 212.0, muted, gate, {}, {})
                gate.finish()
            }
            val beginning = (0 until 8000).filter { abs(near.getOrElse(it + voiced) { 0 }.toInt()) > 1200 }
            val retained = beginning.count { sink.samples[460 * 16 + it] != 0.toShort() }.toDouble() / beginning.size
            if (retained < .9) println("openingNonzeroBy20ms=" + (460 until 1100 step 20).map { t ->
                "$t:${sink.samples.subList(t * 16, (t + 20) * 16).count { it != 0.toShort() }}" })
            println("interrupt lang=$language scale=$scale rate=$rate rainFirst=$rainFirst duckAt=$duckAt interruptions=$interrupts opening=$retained")
            assertEquals(2420 * 16, sink.samples.size)
            assertEquals("real person must still interrupt", 1, interrupts)
            assertTrue("voice candidate too late or false early duck", duckAt in 460..1100)
            assertTrue("lost opening syllables", retained >= .9)
        }
    }
    @Test fun rainCannotEvenDuckPlaybackBeforeSpeechConfirmation() {
        val reference = asset("phrasebook/tickets-fr.wav").drop(44).toByteArray()
        for (name in listOf("1-21189-A-10", "2-73027-A-10")) {
            val rain = pcm(File(root, "app/src/androidTest/assets/rain/$name.wav"))
            val sink = Sink()
            var ducks = 0; var interruptions = 0; var muted = -1L
            val echo = PlaybackEcho(212.0).apply { start(reference); position(0, 0) }
            gate(sink, true).use { gate ->
                val floor = PlaybackFloor(gate::recheckOpening, gate::voiceBeforeDuck, gate::resetVoiceBeforeDuck)
                for (offset in rain.indices step 320) {
                    val now = offset / 16 + 20L
                    echo.position((now - 20).toInt(), now - 20)
                    val frame = rain.copyOfRange(offset, minOf(offset + 320, rain.size))
                    floor.send(frame, frame.size, 16000, now, echo, true, false, 212.0, muted, gate,
                        duck = { if (it) ducks++; muted = if (it) now else -1L }, interrupt = { interruptions++ })
                }
                floor.send(ShortArray(320), 320, 16000, rain.size / 16 + 20L, echo, false, false,
                    212.0, muted, gate, duck = {}, interrupt = {})
                gate.finish()
            }
            println("rain=$name ducks=$ducks interruptions=$interruptions")
            assertEquals(rain.size + 320, sink.samples.size)
            assertTrue(sink.samples.all { it == 0.toShort() })
            assertEquals(0, interruptions)
            assertEquals("rain must not repeatedly mute the speaker", 0, ducks)
        }
    }
}
