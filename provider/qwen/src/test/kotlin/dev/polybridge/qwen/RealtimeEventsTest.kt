package dev.polybridge.qwen

import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test

class RealtimeEventsTest {
    private fun events(side: Side, language: String, finishSourceLate: Boolean): List<LiveUpdate> {
        val output = mutableListOf<LiveUpdate>()
        val bridge = RealtimeEvents(side) { output += it }
        fun send(s: String) = bridge.accept(s)
        send("""{"type":"input_audio_buffer.speech_started","item_id":"u","audio_start_ms":400}""")
        send("""{"type":"conversation.item.input_audio_transcription.text","item_id":"u","text":"Je","stash":" voudrais","language":"$language"}""")
        send("""{"type":"response.created","response":{"id":"r"}}""")
        send("""{"type":"response.audio_transcript.text","response_id":"r","text":"我","stash":"想要"}""")
        val confirmed = """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":"Je voudrais deux billets.","language":"$language"}"""
        if (!finishSourceLate) send(confirmed)
        send("""{"type":"response.audio.delta","response_id":"r","delta":"AAABAA=="}""")
        send("""{"type":"response.audio_transcript.done","response_id":"r","transcript":"我想要两张票。"}""")
        send("""{"type":"response.done","response":{"id":"r","status":"completed"}}""")
        if (finishSourceLate) send(confirmed)
        send("""{"type":"response.done","response":{"id":"r","status":"completed"}}""")
        return output
    }

    @Test fun commitsOneUtteranceForItsSourceLanguageEvenIfRecognitionFinishesLate() {
        for (late in listOf(false,true)) {
            val output = events(Side.FRENCH, "fr", late)
            assertTrue(output.filterIsInstance<LiveUpdate.Caption>().any { it.source == "Je voudrais" })
            val result = output.filterIsInstance<LiveUpdate.Finished>().single()
            assertEquals("Je voudrais deux billets.", result.source)
            assertEquals("我想要两张票。", result.target)
            assertArrayEquals(byteArrayOf(0,0,1,0), result.pcm)
        }
    }
    @Test fun theOppositeOrUncertainLanguageNeverPublishesACaptionOrPlaysAudio() {
        for (code in listOf("fr", "", "en"))
            assertFalse(events(Side.CHINESE, code, false).any { it is LiveUpdate.Caption || it is LiveUpdate.Finished })
    }

    @Test fun textOnlyFrenchPlaybackShowsChineseBeforeTheSentenceFinishes() {
        val updates = mutableListOf<LiveUpdate>()
        val stream = RealtimeEvents(Side.FRENCH) { updates += it }
        listOf(
            """{"type":"input_audio_buffer.speech_started","item_id":"f"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"f","language":"fr","transcript":"Je voudrais deux billets."}""",
            """{"type":"response.created","response":{"id":"r"}}""",
            """{"type":"response.text.text","response_id":"r","text":"我想","stash":"要"}""",
            """{"type":"response.text.done","response_id":"r","text":"我想要两张票。"}""",
            """{"type":"response.done","response":{"id":"r","status":"completed"}}"""
        ).forEach(stream::accept)
        assertTrue(updates.filterIsInstance<LiveUpdate.Caption>().any { it.target == "我想要" })
        assertEquals("我想要两张票。", updates.filterIsInstance<LiveUpdate.Finished>().single().target)
    }
}
