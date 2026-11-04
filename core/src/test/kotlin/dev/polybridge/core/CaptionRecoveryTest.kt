package dev.polybridge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CaptionRecoveryTest {
    private class Feed : LiveSpeechSession {
        lateinit var emit: (LiveUpdate) -> Unit
        override fun connect() = Unit
        override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }
    private fun bad(id: String = "s") = LiveUpdate.Finished(id, Side.CHINESE, 100,
        "未确认", "", byteArrayOf(), TranslationIssue.UNRELIABLE, 300)

    @Test fun anomalousCaptionIsRecheckedOnceWithItsCompleteAudioAndCorrectedLanguage() {
        val feed = Feed()
        val output = CopyOnWriteArrayList<LiveUpdate>()
        var calls = 0
        var clip = byteArrayOf()
        RecoveringCaptionSession({ feed.apply { emit = it } }, { wave ->
            calls++; clip = wave.copyOf()
            RecoveredCaption(Side.FRENCH, "Bonjour, comment allez-vous ?", "你好，最近怎么样？", ModelTrace("test-mt", recoveredBy = "test-asr"))
        }, output::add).use { session ->
            val input = ShortArray(8000) { (it % 30000).toShort() }
            session.send(input, input.size, 16000)
            feed.emit(bad()); feed.emit(bad())
            session.finish()
            val result = output.filterIsInstance<LiveUpdate.Finished>().single()
            assertNull("异常原文没有重新核对", result.issue)
            assertEquals(Side.FRENCH, result.side)
            assertEquals("test-asr", result.model?.recoveredBy)
            assertEquals("Bonjour, comment allez-vous ?", result.source)
            assertEquals(1, calls)
            assertEquals(44 + (300 - 100) * 32, clip.size)
            assertEquals(input[1600], ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN).getShort(44))
            assertEquals(input[4799], ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN).getShort(clip.size - 2))
            assertTrue(output.contains(LiveUpdate.Withdrawn("s", Side.CHINESE)))
        }
    }

    @Test fun expiredAudioIsNotSilentlyRecoveredFromAnIncompleteTail() {
        val feed = Feed(); val output = mutableListOf<LiveUpdate>()
        RecoveringCaptionSession({ feed.apply { emit = it } }, { fail("音频已经过期"); error("unreachable") }, output::add,
            CaptionRecoveryPolicy(bufferMs = 100, maximumClipMs = 100)).use { session ->
            session.send(ShortArray(16000), 16000, 16000)
            feed.emit(bad()); session.finish()
            assertEquals(TranslationIssue.UNRELIABLE, output.filterIsInstance<LiveUpdate.Finished>().single().issue)
        }
    }

    @Test fun ordinaryFollowingSentenceDoesNotWaitForRecoveryAndCloseRejectsLateResults() {
        val feed = Feed(); val output = CopyOnWriteArrayList<LiveUpdate>()
        val began = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val session = RecoveringCaptionSession({ feed.apply { emit = it } }, {
            began.countDown()
            try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* emulate an HTTP reply already in flight */ }
            returned.countDown()
            RecoveredCaption(Side.FRENCH, "Bonjour", "你好")
        }, output::add)
        try {
            session.send(ShortArray(8000), 8000, 16000); feed.emit(bad())
            assertTrue("复核没有启动", began.await(2, TimeUnit.SECONDS))
            val next = LiveUpdate.Finished("next", Side.FRENCH, 350, "Merci", "谢谢", byteArrayOf())
            feed.emit(next)
            assertTrue(output.contains(next))
            session.close()
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertFalse(output.filterIsInstance<LiveUpdate.Finished>().any { it.id == "s" && it.issue == null })
        } finally { release.countDown(); session.close() }
    }

    @Test fun anotherRepetitiveReplyStaysUnconfirmedAndNeverLoopsRetries() {
        val feed = Feed(); val output = CopyOnWriteArrayList<LiveUpdate>(); var calls = 0
        RecoveringCaptionSession({ feed.apply { emit = it } }, {
            calls++; RecoveredCaption(Side.FRENCH, "Il faut avancer maintenant.".repeat(20), "往前走")
        }, output::add).use { session ->
            session.send(ShortArray(8000), 8000, 16000); feed.emit(bad()); session.finish()
            assertEquals(1, calls)
            assertEquals(TranslationIssue.UNRELIABLE, output.filterIsInstance<LiveUpdate.Finished>().single().issue)
        }
    }

    @Test fun missingBoundaryIsNotGuessedAndFortyEightKAudioUsesTheLiveClock() {
        val feed = Feed(); val output = CopyOnWriteArrayList<LiveUpdate>(); var samples = 0; var first: Short = 0
        RecoveringCaptionSession({ feed.apply { emit = it } }, { wave ->
            samples = (wave.size - 44) / 2
            first = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getShort(44)
            RecoveredCaption(Side.FRENCH, "Bonjour", "你好")
        }, output::add).use { session ->
            val input = ShortArray(24000) { (it % 30000).toShort() }
            session.send(input, input.size, 48000)
            feed.emit(bad("missing").copy(endedMs = null)); feed.emit(bad("complete")); session.finish()
            assertEquals(3200, samples); assertEquals(4801.toShort(), first)
            assertEquals(TranslationIssue.UNRELIABLE, output.filterIsInstance<LiveUpdate.Finished>().first().issue)
        }
    }

    @Test fun aFortySecondSentenceIsNeverSilentlyTruncatedByTheHeldSpeechLimit() {
        val feed = Feed(); var bytes = 0
        RecoveringCaptionSession({ feed.apply { emit = it } }, { wave ->
            bytes = wave.size; RecoveredCaption(Side.FRENCH, "Bonjour", "你好")
        }, {}).use { session ->
            session.send(ShortArray(41 * 16000), 41 * 16000, 16000)
            feed.emit(bad().copy(startedMs = 0, endedMs = 40_000)); session.finish()
            assertEquals(44 + 40 * 16000 * 2, bytes)
        }
    }

    @Test fun saturationAndFinishTimeoutStayBoundedAndNeverPublishALateCorrection() {
        val feed = Feed(); val output = CopyOnWriteArrayList<LiveUpdate>()
        val began = CountDownLatch(1); val release = CountDownLatch(1); var calls = 0
        RecoveringCaptionSession({ feed.apply { emit = it } }, {
            calls++; began.countDown(); release.await(2, TimeUnit.SECONDS)
            RecoveredCaption(Side.FRENCH, "Bonjour", "你好")
        }, output::add, CaptionRecoveryPolicy(maximumPending = 1, finishWaitMs = 20)).use { session ->
            session.send(ShortArray(8000), 8000, 16000); feed.emit(bad("slow"))
            assertTrue(began.await(2, TimeUnit.SECONDS))
            feed.emit(bad("overflow")); session.finish()
            assertEquals(setOf("slow", "overflow"), output.filterIsInstance<LiveUpdate.Finished>().map { it.id }.toSet())
            assertTrue(output.filterIsInstance<LiveUpdate.Finished>().all { it.issue == TranslationIssue.UNRELIABLE })
            release.countDown(); session.finish()
            assertEquals(1, calls)
            assertEquals(2, output.filterIsInstance<LiveUpdate.Finished>().size)
        }
    }

    @Test fun finishWaitsUntilTheCorrectedResultHasActuallyBeenDelivered() {
        val feed = Feed(); val inCallback = CountDownLatch(1); val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        RecoveringCaptionSession({ feed.apply { emit = it } }, {
            RecoveredCaption(Side.FRENCH, "Bonjour", "你好")
        }, { event -> if (event is LiveUpdate.Finished) { inCallback.countDown(); release.await(2, TimeUnit.SECONDS) } }).use { session ->
            session.send(ShortArray(8000), 8000, 16000); feed.emit(bad())
            assertTrue(inCallback.await(2, TimeUnit.SECONDS))
            val finishing = Thread { session.finish(); finished.countDown() }.apply { start() }
            try { assertFalse("尚未保存结果就结束了会话", finished.await(50, TimeUnit.MILLISECONDS)) }
            finally { release.countDown(); finishing.join(2000) }
            assertEquals(0L, finished.count)
        }
    }
}
