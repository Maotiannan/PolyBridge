package dev.polybridge.qwen

import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test

class RealtimeEventsTest {
    @Test fun aLoopingRecognitionIsNeverPublishedAsAnOrdinarySuccessfulCaption() {
        val output = mutableListOf<LiveUpdate>()
        val events = RealtimeEvents(Side.CHINESE, output::add)
        val repeated = "请继续向前走，come on。".repeat(16)
        events.accept("""{"type":"input_audio_buffer.speech_started","item_id":"s","audio_start_ms":20}""")
        events.accept("""{"type":"response.created","response":{"id":"r"}}""")
        events.accept("""{"type":"conversation.item.input_audio_transcription.text","item_id":"s","language":"zh","text":"你好。","stash":""}""")
        events.accept("""{"type":"conversation.item.input_audio_transcription.text","item_id":"s","language":"zh","text":"你好。$repeated","stash":""}""")
        assertFalse("异常循环原文直接刷进了浮窗", output.filterIsInstance<LiveUpdate.Caption>().any { repeated in it.source })
        events.accept("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"s","language":"zh","transcript":"你好。$repeated"}""")
        events.accept("""{"type":"response.text.done","response_id":"r","text":"Bonjour."}""")
        events.accept("""{"type":"response.done","response":{"id":"r","status":"completed"}}""")
        assertNotNull("循环识别被标记为正常完成", output.filterIsInstance<LiveUpdate.Finished>().single().issue)
    }

    @Test fun aLoopingTranslationIsNotSavedAsAValidTranslation() {
        val output = mutableListOf<LiveUpdate>()
        val events = RealtimeEvents(Side.FRENCH, output::add)
        val repeated = "请在车站等我，我们马上就到。".repeat(16)
        events.accept("""{"type":"input_audio_buffer.speech_started","item_id":"s"}""")
        events.accept("""{"type":"response.created","response":{"id":"r"}}""")
        events.accept("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"s","language":"fr","transcript":"Attendez-moi à la gare."}""")
        events.accept("""{"type":"response.text.done","response_id":"r","text":"$repeated"}""")
        events.accept("""{"type":"response.done","response":{"id":"r","status":"completed"}}""")
        assertNotNull("循环译文被当成正常完成", output.filterIsInstance<LiveUpdate.Finished>().single().issue)
    }

    @Test fun updatedSnapshotsAndOrdinaryRepeatedWordsArePreservedWithoutAccumulatingOldPreviews() {
        val output = mutableListOf<LiveUpdate>()
        val events = RealtimeEvents(Side.FRENCH, output::add)
        events.accept("""{"type":"input_audio_buffer.speech_started","item_id":"s"}""")
        events.accept("""{"type":"response.created","response":{"id":"r"}}""")
        for (word in listOf("Allez", "Allez, allez", "Allez, allez, allez !")) {
            events.accept("""{"type":"conversation.item.input_audio_transcription.text","item_id":"s","language":"fr","text":"$word","stash":""}""")
        }
        for (word in listOf("走", "走，走", "走，走，走！")) {
            events.accept("""{"type":"response.text.text","response_id":"r","text":"$word","stash":""}""")
        }
        assertEquals("Allez, allez, allez !", output.filterIsInstance<LiveUpdate.Caption>().last().source)
        assertEquals("走，走，走！", output.filterIsInstance<LiveUpdate.Caption>().last().target)
        events.accept("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"s","transcript":"Allez, allez, allez !"}""")
        events.accept("""{"type":"response.text.done","response_id":"r","text":"走，走，走！"}""")
        events.accept("""{"type":"response.done","response":{"id":"r","status":"completed"}}""")
        assertNull(output.filterIsInstance<LiveUpdate.Finished>().single().issue)
    }

    @Test fun aCancelledTranslationStillKeepsTheConfirmedOriginalWhenTheNextTurnStarts() {
        for (early in listOf(true, false)) {
            val updates = mutableListOf<LiveUpdate>()
            val events = RealtimeEvents(Side.FRENCH, updates::add)
            events.accept("""{"type":"input_audio_buffer.speech_started","item_id":"s"}""")
            events.accept("""{"type":"response.created","response":{"id":"r"}}""")
            events.accept("""{"type":"response.text.text","response_id":"r","text":"两张","stash":""}""")
            val source = """{"type":"conversation.item.input_audio_transcription.completed","item_id":"s","language":"fr","transcript":"deux billets"}"""
            if (early) events.accept(source)
            events.accept("""{"type":"response.done","response":{"id":"r","status":"cancelled"}}""")
            if (!early) events.accept(source)
            val saved = updates.filterIsInstance<LiveUpdate.Finished>().single()
            assertEquals("deux billets", saved.source)
            assertEquals("两张", saved.target)
            assertEquals(TranslationIssue.INTERRUPTED, saved.issue)
            assertTrue(saved.pcm.isEmpty())
        }
    }

    @Test fun theFinalResponseMayCarryTheTranslationInsteadOfASeparateTextEvent() {
        val updates = mutableListOf<LiveUpdate>()
        val events = RealtimeEvents(Side.FRENCH, updates::add)
        events.accept("""{"type":"input_audio_buffer.speech_started","item_id":"s"}""")
        events.accept("""{"type":"response.created","response":{"id":"r"}}""")
        events.accept("""{"type":"response.done","response":{"id":"r","status":"completed","output":[{"content":[{"type":"text","text":"车站"}]}]}}""")
        events.accept("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"s","language":"fr","transcript":"la gare"}""")
        assertEquals("车站", updates.filterIsInstance<LiveUpdate.Finished>().single().target)
    }

    @Test fun aFailedNoiseHypothesisIsWithdrawnFromTheTranscript() {
        val updates = mutableListOf<LiveUpdate>()
        val events = RealtimeEvents(Side.FRENCH, updates::add)
        events.accept("""{"type":"input_audio_buffer.speech_started","item_id":"noise"}""")
        events.accept("""{"type":"conversation.item.input_audio_transcription.text","item_id":"noise","language":"fr","text":"","stash":"oh"}""")
        events.accept("""{"type":"conversation.item.input_audio_transcription.failed","item_id":"noise"}""")
        assertEquals(2, updates.size)
        assertTrue(updates.first() is LiveUpdate.Caption)
        assertEquals(LiveUpdate.Withdrawn("noise", Side.FRENCH), updates.last())
    }
    @Test fun anOversizedVoiceCanStillFinishItsTextAndTheNextSentence() {
        val out = mutableListOf<LiveUpdate>()
        val events = RealtimeEvents(Side.FRENCH, out::add)
        val audio = java.util.Base64.getEncoder().encodeToString(ByteArray(80 * 1024))
        for (id in listOf("long", "next")) {
            events.accept("""{"type":"input_audio_buffer.speech_started","item_id":"$id"}""")
            events.accept("""{"type":"response.created","response":{"id":"r$id"}}""")
            events.accept("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id","language":"fr","transcript":"du texte"}""")
            repeat(if (id == "long") 52 else 1) { events.accept("""{"type":"response.audio.delta","response_id":"r$id","delta":"$audio"}""") }
            events.accept("""{"type":"response.audio_transcript.done","response_id":"r$id","transcript":"文字"}""")
            events.accept("""{"type":"response.done","response":{"id":"r$id","status":"completed"}}""")
        }
        val finals = out.filterIsInstance<LiveUpdate.Finished>()
        assertEquals(listOf("long", "next"), finals.map { it.id })
        assertTrue(finals[0].pcm.isEmpty())
        assertEquals(80 * 1024, finals[1].pcm.size)
    }
    @Test fun terminalEventsInDifferentOrdersRetainBothSentencesAcrossABreath() {
        val output = mutableListOf<LiveUpdate>()
        val events = RealtimeEvents(Side.FRENCH, output::add)
        fun send(raw: String) = events.accept(raw)
        for (id in listOf("a", "b")) {
            send("""{"type":"input_audio_buffer.speech_started","item_id":"$id","audio_start_ms":${if (id == "a") 100 else 1900}}""")
            send("""{"type":"conversation.item.input_audio_transcription.text","item_id":"$id","language":"fr","text":"phrase $id","stash":""}""")
            send("""{"type":"response.created","response":{"id":"r$id"}}""")
        }
        // A final ASR event can omit metadata already supplied in its live preview.
        send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"phrase a"}""")
        send("""{"type":"response.done","response":{"id":"ra","status":"completed"}}""")
        send("""{"type":"response.text.done","response_id":"rb","text":"第二句"}""")
        send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"b","language":"fr","transcript":"phrase b"}""")
        send("""{"type":"response.done","response":{"id":"rb","status":"completed"}}""")
        send("""{"type":"response.text.done","response_id":"ra","text":"第一句"}""")
        assertEquals(mapOf("a" to "第一句", "b" to "第二句"), output.filterIsInstance<LiveUpdate.Finished>().associate { it.id to it.target })
    }
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
