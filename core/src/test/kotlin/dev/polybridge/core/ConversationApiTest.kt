package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test

class ConversationApiTest {
    @Test fun languageDirectionFollowsPressedSide() {
        assertEquals("zh-CN", Side.CHINESE.source)
        assertEquals("fr-FR", Side.CHINESE.target)
        assertEquals("zh-CN", Side.FRENCH.target)
    }

    @Test fun onePressOwnsTheMicrophoneAndCancelRemovesOnlyThatTurn() {
        val turns = Conversation().press("a", Side.CHINESE)
        assertEquals(turns, turns.press("b", Side.FRENCH))
        assertEquals(turns, turns.cancel("b"))
        assertTrue(turns.cancel("a").turns.isEmpty())
    }

    @Test fun repeatedReleaseCannotSubmitTwiceAndLegacyExamplesKeepTheirLabel() {
        val pending = Conversation().press("a", Side.FRENCH).captureEnded("a", Verdict.SIGNAL)
        assertEquals(TurnStatus.TRANSCRIBING, pending.turns.single().status)
        assertEquals(pending, pending.captureEnded("a", Verdict.SIGNAL))
        val legacy = """{"turns":[{"id":"old","side":"FRENCH","status":"DEMO_READY","sourceText":"Merci","translatedText":"谢谢"}]}"""
        val restored = ReportCodec.readChat(legacy).recoverInterrupted().turns.single()
        assertEquals(TurnStatus.DEMO_READY, restored.status)
        assertEquals("Merci", restored.sourceText)
    }

    @Test fun silenceAndInterruptionsNeverPublishSuccess() {
        val open = Conversation().press("b", Side.CHINESE)
        assertEquals(TurnStatus.FAILED, open.captureEnded("b", Verdict.SILENT).turns.single().status)
        assertEquals(TurnStatus.FAILED, open.recoverInterrupted().turns.single().status)
        assertTrue(open.captureEnded("b", Verdict.CANCELLED).turns.isEmpty())
    }
}
