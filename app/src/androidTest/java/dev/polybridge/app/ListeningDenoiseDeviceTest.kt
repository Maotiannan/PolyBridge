package dev.polybridge.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.SpeechGate
import dev.polybridge.core.LiveSpeechSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** No microphone, account, playback, or personal preferences. Runs the shipped Android native models. */
@RunWith(AndroidJUnit4::class)
class ListeningDenoiseDeviceTest {
    @Before fun keepNativeChecksInForeground() { openProjectLauncher() }
    @Test fun bundledEnhancementKeepsQuietFrenchAndChineseAndRejectsRain() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        fun wave(bytes: ByteArray): ShortArray {
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rate = input.getInt(24)
            return ShortArray((bytes.size - 44) / 2 * 16000 / rate) { i ->
                val at = i * rate / 16000.0
                val index = at.toInt()
                val a = input.getShort(44 + index * 2)
                val b = input.getShort(minOf(bytes.size - 2, 44 + (index + 1) * 2))
                (a * (1 - at + index) + b * (at - index)).roundToInt().toShort()
            }
        }
        val noise = inst.context.assets.open("rain/2-73027-A-10.wav").use { wave(it.readBytes()) }
        for (language in listOf("fr", "zh")) for (rate in listOf(16000, 48000)) {
            val voice = app.assets.open("phrasebook/train-$language.wav").use { wave(it.readBytes()) }
            val lead = 32000
            val source = ShortArray(lead + voice.size + 16000) { i ->
                ((voice.getOrNull(i - lead) ?: 0) * .14 + noise[i % noise.size] * .03).roundToInt().toShort()
            }
            val received = ArrayList<Short>()
            var finishes = 0; var closes = 0
            val sink = object : LiveSpeechSession {
                override fun connect() = Unit
                override fun send(samples: ShortArray, length: Int, rate: Int) {
                    assertEquals(16000, rate); repeat(length) { received += samples[it] }
                }
                override fun finish() { finishes++ }
                override fun close() { closes++ }
            }
            val began = SystemClock.elapsedRealtime()
            SpeechGate(app, sink, enhanced = true).use { filter ->
                val stride = rate / 16000
                for (offset in 0 until source.size * stride step 137) {
                    val frame = ShortArray(minOf(137, source.size * stride - offset)) { source[(offset + it) / stride] }
                    filter.send(frame, frame.size, rate)
                }
                assertTrue("must stream before finish", received.size > lead)
                filter.finish(); filter.finish()
            }
            assertEquals(source.size, received.size); assertEquals(1, finishes); assertEquals(1, closes)
            assertTrue("rain leaked", received.take(lead - 4000).all { it == 0.toShort() })
            val opening = (0 until minOf(16000, voice.size)).filter { abs(voice[it].toInt()) > 1200 }
            val retention = opening.count { received[lead + it] != 0.toShort() }.toDouble() / opening.size
            assertTrue("quiet opening lost", retention >= .9)
            inst.sendStatus(0, Bundle().apply { putString("denoise", "language=$language rate=$rate opening=$retention elapsedMs=${SystemClock.elapsedRealtime() - began} audioMs=${source.size / 16}") })
        }
    }
}
