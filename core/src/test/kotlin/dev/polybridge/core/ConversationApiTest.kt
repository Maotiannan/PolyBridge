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

    @Test fun repeatedCompletionCannotDuplicateTheDemoTranslation() {
        val open = Conversation().press("a", Side.FRENCH)
        val complete = open.finish("a", Verdict.SIGNAL, DemoTranslation)
        assertEquals(1, complete.turns.size)
        val turn = complete.turns.single()
        assertEquals(TurnStatus.DEMO_READY, turn.status)
        assertTrue(turn.sourceText.isNotBlank())
        assertTrue(turn.translatedText.isNotBlank())
        assertEquals(complete, complete.finish("a", Verdict.SIGNAL, DemoTranslation))
    }

    @Test fun silenceAndInterruptionsNeverPublishDemoSuccess() {
        val open = Conversation().press("b", Side.CHINESE)
        assertEquals(TurnStatus.FAILED, open.finish("b", Verdict.SILENT, DemoTranslation).turns.single().status)
        assertEquals(TurnStatus.FAILED, open.recoverInterrupted().turns.single().status)
        assertTrue(open.finish("b", Verdict.CANCELLED, DemoTranslation).turns.isEmpty())
    }
}
