package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test

class TranslationTurnsTest {
    @Test fun sessionsKeepOldMessagesOutOfTheHomeScreenWithoutRemovingHistory() {
        val old = ReportCodec.readChat("""{"turns":[{"id":"demo","side":"FRENCH","status":"DEMO_READY","sourceText":"Salut","translatedText":"你好"}]}""")
        assertTrue(old.currentTurns.isEmpty())
        val current = old.typed("new", Side.CHINESE, "今天几号？")
        assertEquals(listOf("new"), current.currentTurns.map(Turn::id))
        val fresh = ReportCodec.readChat(ReportCodec.saveChat(current.newSession()))
        assertTrue(fresh.currentTurns.isEmpty())
        assertEquals(2, fresh.turns.size)
    }
    @Test fun interruptedTranslationCanResumeFromTextAndIgnoresLateCompletions() {
        val open = Conversation().press("a", Side.FRENCH).captureEnded("a", Verdict.SIGNAL)
        assertEquals(TurnStatus.TRANSCRIBING, open.turns.single().status)
        val known = open.transcribed("a", "Deux places.").recoverInterrupted()
        assertEquals("Deux places.", known.turns.single().sourceText)
        assertEquals(TurnStatus.FAILED, known.turns.single().status)
        assertEquals(known, known.translated("a", "Deux places.", "过期译文"))
        val fixed = known.retranslate("a", "Trois places.")
        assertEquals(fixed, fixed.translated("a", "Deux places.", "过期译文"))
        val final = fixed.translated("a", "Trois places.", "三个座位。")
        assertEquals(TurnStatus.READY, final.turns.single().status)
        assertEquals(final, final.translated("a", "Trois places.", "重复结果"))
    }
    @Test fun cancelsAndSilenceCannotTurnIntoCloudRequests() {
        for (end in listOf(Verdict.CANCELLED, Verdict.SILENT, Verdict.ROUTE_MISMATCH)) {
            val chat = Conversation().press("a", Side.CHINESE).captureEnded("a", end)
            assertFalse(chat.turns.any { it.status == TurnStatus.TRANSCRIBING })
            assertEquals(chat, chat.transcribed("a", "无效原文"))
        }
    }
}
