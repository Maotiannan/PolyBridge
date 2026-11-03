package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReconnectingLiveTest {
    @Test fun reconnectsWithoutReplayingOldFramesOrAcceptingOldSocketResults() {
        val frames = mutableListOf<Int>()
        val events = mutableListOf<LiveUpdate>()
        val listeners = mutableListOf<(LiveUpdate) -> Unit>()
        val reopened = CountDownLatch(1)
        val live = ReconnectingLiveSession(factory = { consume ->
            listeners += consume
            object : LiveSpeechSession {
                override fun connect() = Unit
                override fun send(samples: ShortArray, length: Int, rate: Int) { frames += samples[0].toInt() }
                override fun finish() = Unit
                override fun close() = Unit
            }
        }, update = { events += it; if (it == LiveUpdate.Reconnected) reopened.countDown() }, delays = longArrayOf(0))
        try {
            live.connect(); live.send(shortArrayOf(5),1,16000)
            listeners.first()(LiveUpdate.Stopped(TranslationIssue.NETWORK))
            assertTrue(reopened.await(2, TimeUnit.SECONDS))
            listeners.first()(LiveUpdate.Caption("stale", Side.CHINESE, "过期", ""))
            live.send(shortArrayOf(7),1,16000)
            assertEquals(listOf(5,7), frames)
            assertFalse(events.any { it is LiveUpdate.Caption })
            assertEquals(1, events.filterIsInstance<LiveUpdate.Reconnecting>().size)
        } finally { live.close() }
    }
}
