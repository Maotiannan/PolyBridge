package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryTest {
    private fun add(chat: Conversation, group: String, kind: Kind, index: Int): Conversation {
        val offset = index * 1200L
        val line = LiveUpdate.Caption("live-$group-$index", Side.FRENCH, "Phrase $index", "第 $index 句", offset)
        return chat.heard(line, LiveOrigin(group, kind, offset))
    }

    @Test fun fullLastRecordingPerModeSurvivesHistoryLimitRestartAndUndo() {
        var chat = (1..125).fold(Conversation()) { state, i -> add(state, "podcast", Kind.CAPTIONS, i) }
        chat = (1..121).fold(chat) { state, i -> add(state, "room", Kind.LISTEN, i) }
        chat = ReportCodec.readChat(ReportCodec.saveChat(chat)).recoverInterrupted()
        assertEquals((1..125).map { "Phrase $it" }, chat.turns.filter { it.live?.captureId == "podcast" }.map { it.sourceText })
        assertEquals(121, chat.turns.count { it.live?.captureId == "room" })
        assertEquals(chat.turns, Conversation().restoreHistory(chat).turns)
        val next = add(chat, "new-podcast", Kind.CAPTIONS, 1)
        assertTrue(next.turns.none { it.live?.captureId == "podcast" })
        assertEquals(121, next.turns.count { it.live?.captureId == "room" })
        val old = ReportCodec.readChat("""{"turns":[{"id":"pre-upgrade","side":"FRENCH","status":"READY","sourceText":"Salut","translatedText":"你好"}]}""")
        assertNull(old.turns.single().live)
        assertEquals("你好", add(old, "new", Kind.CAPTIONS, 1).turns.first().translatedText)
    }
}
