package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test

class ConfirmedSourceHistoryTest {
    private val origin = LiveOrigin("media", Kind.CAPTIONS, 15)
    private val source = LiveUpdate.Caption("a", Side.FRENCH, "Bonjour.", "", 15, sourceFinal = true)

    @Test fun sourceIsPersistedWhileTranslationIsPendingAndCompletedOnlyOnce() {
        val pending = Conversation().heardSource(source, origin)
        assertEquals(TurnStatus.TRANSLATING, pending.turns.single().status)
        val restored = ReportCodec.readChat(ReportCodec.saveChat(pending))
        assertEquals("Bonjour.", restored.turns.single().sourceText)
        assertEquals(TranslationIssue.INTERRUPTED, restored.recoverInterrupted().turns.single().issue)
        val done = pending.heard(source.copy(target = "你好。"), origin)
        assertEquals(TurnStatus.READY, done.turns.single().status)
        assertEquals("你好。", done.turns.single().translatedText)
        assertEquals(done, done.heard(source.copy(target = "早上好。"), origin))
    }

    @Test fun aConflictingCompletionDoesNotChangeOriginalOrAttachWrongTranslation() {
        val pending = Conversation().heardSource(source, origin)
        val done = pending.heard(source.copy(source = "Bonsoir.", target = "晚上好。"), origin)
        assertEquals("Bonjour.", done.turns.single().sourceText)
        assertEquals("", done.turns.single().translatedText)
        assertEquals(TranslationIssue.UNRELIABLE, done.turns.single().issue)
    }

    @Test fun provisionalSourceIsNeverPersisted() {
        assertTrue(Conversation().heardSource(source.copy(sourceFinal = false), origin).turns.isEmpty())
    }
}
