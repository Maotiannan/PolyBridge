package dev.polybridge.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class LiveListeningTest {
    @Test fun oneSessionRecognizesAlternatingChineseAndFrench() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveListen") == "true")
        val activity = openProjectLauncher()
        val app = activity.application as PolyBridgeApp
        val inst = InstrumentationRegistry.getInstrumentation()
        val keepDisplay = args.getString("keepDisplay") != "false"
        val events = CopyOnWriteArrayList<LiveUpdate>()
        val api = requireNotNull(app.settings.provider())
        val session = api.live {
            events += it
            if (it is LiveUpdate.Finished) {
                android.util.Log.i("PolyBridgeLiveQA", "Fixture committed: " + it.side.source)
                log("committed", it.side.source + " | " + it.source)
            }
            if (it is LiveUpdate.Reconnecting) log("reconnecting", "attempt=" + it.attempt)
        }
        val count = args.getString("turns")?.toIntOrNull()?.coerceIn(2, 60) ?: 2
        val minimumGapMs = args.getString("gapMs")?.toIntOrNull()?.coerceIn(800, 60000) ?: 1000
        val silence = ShortArray(1600)
        fun gap(duration: Int) {
            repeat((duration + 99) / 100) { session.send(silence, silence.size, 16000); SystemClock.sleep(100) }
        }
        try {
            if (keepDisplay) inst.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            session.connect()
            val began = SystemClock.elapsedRealtime()
            repeat(count) { index ->
                val side = Side.entries[index % 2]
                val name = if ((index / 2) % 2 == 0) "tickets" else "station"
                val audio = app.assets.open("phrasebook/$name-${side.source.substringBefore('-')}.wav").use { it.readBytes() }
                val input = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
                val rate = input.getInt(24)
                val total = ((audio.size-44)/2).toLong() * 16000 / rate
                var i = 0
                while (i < total) {
                    val frame = ShortArray(minOf(1600L, total - i).toInt()) { offset ->
                        input.getShort(44 + 2 * ((i.toLong()+offset)*rate/16000).toInt())
                    }
                    session.send(frame, frame.size, 16000)
                    SystemClock.sleep(frame.size * 1000L / 16000)
                    i += frame.size
                }
                gap(minimumGapMs)
            }
            session.finish()
            val complete = events.filterIsInstance<LiveUpdate.Finished>()
            val captions = events.filterIsInstance<LiveUpdate.Caption>()
            assertTrue("看不到增量字幕", captions.isNotEmpty())
            assertEquals(events.filterIsInstance<LiveUpdate.Stopped>().toString(), count, complete.size)
            assertEquals(count, complete.map { it.id }.distinct().size)
            complete.forEachIndexed { i, event ->
                assertEquals(Side.entries[i % 2], event.side)
                assertTrue(event.pcm.size > 2000)
                assertTrue(event.source.isNotBlank() && event.target.isNotBlank())
                log("liveSentence", "${event.side.source} | ${event.source} | ${event.target} | ${event.pcm.size} bytes")
            }
            log("liveTotal", "elapsedMs=${SystemClock.elapsedRealtime()-began}, captions=${captions.size}, final=${complete.size}")
        } finally {
            session.close()
            if (keepDisplay) inst.runOnMainSync { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private fun log(key: String, text: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, text) })
    }
}
