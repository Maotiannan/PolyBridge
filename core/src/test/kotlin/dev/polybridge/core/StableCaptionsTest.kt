package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test

class StableCaptionsTest {
    @Test fun anomalousDraftShowsOnlyWarningAndCanBeReplacedByUnpublishedAudioRecovery() {
        val seen = mutableListOf<LiveUpdate>()
        val stable = StableCaptions(seen::add)
        stable.accept(caption().copy(source = "猜测重复文字", issue = TranslationIssue.UNRELIABLE))
        val warning = seen.singleOrNull() as? LiveUpdate.Caption
        assertNotNull("异常不能静默消失", warning)
        assertEquals("", warning?.source)
        assertEquals("", warning?.target)
        stable.accept(LiveUpdate.Withdrawn("a", Side.FRENCH))
        stable.accept(finished().copy(side = Side.CHINESE, source = "你好。", target = "Bonjour."))
        assertTrue(seen.any { it is LiveUpdate.Withdrawn })
        assertEquals("你好。", (seen.last() as LiveUpdate.Finished).source)
        assertNull((seen.last() as LiveUpdate.Finished).issue)
    }
    @Test fun unfinishedAnomalyKeepsItsFailureMarkerButNeverItsGuessedText() {
        val seen = mutableListOf<LiveUpdate>()
        val stable = StableCaptions(seen::add)
        stable.accept(caption().copy(source = "猜测文字", issue = TranslationIssue.UNRELIABLE))
        stable.accept(LiveUpdate.Stopped(TranslationIssue.INTERRUPTED))
        val failed = seen.filterIsInstance<LiveUpdate.Finished>().single()
        assertEquals("", failed.source)
        assertEquals(TranslationIssue.UNRELIABLE, failed.issue)
    }
    @Test fun pendingOverflowBecomesExplicitFailureWithoutLosingOriginals() {
        val seen = mutableListOf<LiveUpdate>()
        val stable = StableCaptions(seen::add)
        repeat(40) { stable.accept(caption(true).copy(id = "s$it")) }
        assertEquals(40, seen.filterIsInstance<LiveUpdate.Caption>().size)
        assertEquals(8, seen.filterIsInstance<LiveUpdate.Finished>().size)
        assertTrue(seen.filterIsInstance<LiveUpdate.Finished>().all { it.issue == TranslationIssue.LIMIT && it.source == source })
        stable.accept(LiveUpdate.Stopped(TranslationIssue.INTERRUPTED))
        assertEquals(40, seen.filterIsInstance<LiveUpdate.Finished>().size)
    }
    private val source = "Je ne veux pas trois billets."
    private fun caption(final: Boolean = false) = LiveUpdate.Caption("a", Side.FRENCH, source, "我要", 10, sourceFinal = final)
    private fun finished(issue: TranslationIssue? = null) = LiveUpdate.Finished("a", Side.FRENCH, 10,
        source, "我不想要三张票。", byteArrayOf(), issue)

    @Test fun provisionalTextIsInvisibleAndConfirmedSourceAppearsBeforeTranslation() {
        val seen = mutableListOf<LiveUpdate>()
        val stable = StableCaptions(seen::add)
        stable.accept(caption())
        assertTrue(seen.isEmpty())
        stable.accept(caption(true))
        assertEquals(source, (seen.single() as LiveUpdate.Caption).source)
        assertEquals("", (seen.single() as LiveUpdate.Caption).target)
        stable.accept(caption(true).copy(target = "修订中的译文"))
        assertEquals(1, seen.size)
        stable.accept(finished())
        assertEquals("我不想要三张票。", (seen.last() as LiveUpdate.Finished).target)
    }

    @Test fun cancelledTranslationNeverPublishesAPartialTarget() {
        val seen = mutableListOf<LiveUpdate>()
        val stable = StableCaptions(seen::add)
        stable.accept(caption(true))
        stable.accept(finished(TranslationIssue.INTERRUPTED))
        val last = seen.last() as LiveUpdate.Finished
        assertEquals(source, last.source)
        assertEquals("", last.target)
        assertEquals(TranslationIssue.INTERRUPTED, last.issue)
    }

    @Test fun withdrawalAndLaterRewriteCannotChangeAlreadyPublishedWords() {
        val seen = mutableListOf<LiveUpdate>()
        val stable = StableCaptions(seen::add)
        stable.accept(caption(true))
        stable.accept(LiveUpdate.Withdrawn("a", Side.FRENCH))
        stable.accept(finished().copy(source = "Je veux trois billets."))
        assertFalse(seen.any { it is LiveUpdate.Withdrawn })
        val last = seen.last() as LiveUpdate.Finished
        assertEquals(source, last.source)
        assertEquals("", last.target)
        assertEquals(TranslationIssue.UNRELIABLE, last.issue)
        stable.accept(finished())
        assertEquals(2, seen.size)
    }

    @Test fun loopingSourceFallbackIsNotCertifiedAsOriginal() {
        val seen = mutableListOf<LiveUpdate>()
        StableCaptions(seen::add).accept(finished(TranslationIssue.UNRELIABLE).copy(sourceFinal = false))
        assertEquals("", (seen.single() as LiveUpdate.Finished).source)
    }

    @Test fun reconnectClosesPendingOriginalThenAllowsReusedServerIds() {
        val seen = mutableListOf<LiveUpdate>()
        val stable = StableCaptions(seen::add)
        stable.accept(caption(true))
        stable.accept(LiveUpdate.Reconnecting(1))
        val lost = seen.filterIsInstance<LiveUpdate.Finished>().single()
        assertEquals(source, lost.source)
        assertEquals(TranslationIssue.NETWORK, lost.issue)
        stable.accept(LiveUpdate.Reconnected)
        stable.accept(finished())
        assertEquals(2, seen.filterIsInstance<LiveUpdate.Finished>().size)
    }
}
