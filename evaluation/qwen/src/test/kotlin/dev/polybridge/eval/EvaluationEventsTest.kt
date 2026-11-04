package dev.polybridge.eval

import org.junit.Assert.*
import org.junit.Test

class EvaluationEventsTest {
    @Test fun recordsServerModelAndClassifiesFailureWithoutEchoingRawMessage() {
        val events = EvaluationEvents()
        events.accept("""{"type":"session.created","session":{"id":"sess_12345678","model":"qwen3.8-omni-flash-realtime"}}""", 1)
        events.accept("""{"type":"error","error":{"code":"COMMON_ERROR","message":"Model is not supported: confidential sentinel"}}""", 2)
        assertEquals(listOf("qwen3.8-omni-flash-realtime"), events.snapshot().serverModels)
        assertEquals("MODEL_UNAVAILABLE", events.snapshot().errorCategory)
        assertEquals("sess_12345678", events.snapshot().sessionId)
        assertFalse(events.snapshot().toString().contains("confidential"))
    }

    @Test fun audio31TranscriptionDeltaUsesSnapshotFields() {
        val events = EvaluationEvents()
        events.accept("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"a","text":"Bon","stash":"jour"}""", 10)
        assertEquals(listOf("Bonjour"), events.snapshot().sources)
        events.accept("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"a","text":"Bonjour","stash":"."}""", 20)
        assertEquals(listOf("Bonjour."), events.snapshot().sources)
    }

    @Test fun incompleteResponsePreventsUsageSettlement() {
        val events = EvaluationEvents()
        events.accept("""{"type":"response.done","response":{"id":"r1","status":"completed","usage":{"input_tokens":10,"output_tokens":3,"input_tokens_details":{"audio_tokens":10},"output_tokens_details":{"text_tokens":3}}}}""", 10)
        events.accept("""{"type":"response.created","response":{"id":"r2","status":"in_progress"}}""", 20)
        assertFalse(events.snapshot().usageComplete)
    }

    @Test fun snapshotsReplaceButDeltasAppendAndFinalsAreNotDuplicated() {
        val events = EvaluationEvents()
        events.accept("""{"type":"response.text.text","item_id":"a","text":"你","stash":"好"}""", 10)
        events.accept("""{"type":"response.text.text","item_id":"a","text":"你好","stash":"。"}""", 20)
        events.accept("""{"type":"response.text.done","item_id":"a","text":"你好。"}""", 30)
        events.accept("""{"type":"response.text.delta","item_id":"b","delta":"谢谢"}""", 40)
        events.accept("""{"type":"response.text.delta","item_id":"b","delta":"。"}""", 50)
        events.accept("""{"type":"response.text.done","item_id":"b","text":"谢谢。"}""", 60)
        events.accept("""{"type":"response.text.done","item_id":"b","text":"谢谢。"}""", 70)
        assertEquals(listOf("你好。", "谢谢。"), events.snapshot().targets)
        assertEquals(2, events.snapshot().targetFinals)
        assertEquals(10L, events.snapshot().firstTargetMs)
    }

    @Test fun lateOriginalsAndDuplicateUsageArePreservedIndependently() {
        val events = EvaluationEvents()
        val done = """{"type":"response.done","response":{"id":"r","status":"completed","usage":{"input_tokens":15,"output_tokens":3,"input_tokens_details":{"audio_tokens":10,"text_tokens":5},"output_tokens_details":{"text_tokens":3,"audio_tokens":0}}}}"""
        events.accept(done, 20); events.accept(done, 30)
        events.accept("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"s","transcript":"Bonjour."}""", 40)
        assertEquals(listOf("Bonjour."), events.snapshot().sources)
        assertEquals(1, events.snapshot().responses)
        assertEquals(10L, events.snapshot().usage.inputAudio)
        assertTrue(events.snapshot().usageComplete)
    }

    @Test fun cumulativeAsrUsageIsNotAddedAndHeartbeatDoesNotCreateSentence() {
        val events = EvaluationEvents()
        for ((id, tokens) in listOf(1 to 10, 2 to 20)) {
            events.accept("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":$id,"text":"Phrase $id","sentence_end":true}},"usage":{"input_tokens":$tokens,"output_tokens":$id,"total_tokens":${tokens + id}}}}""", id * 10L)
        }
        events.accept("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":0,"heartbeat":true,"text":""}}}}""", 30)
        assertEquals(listOf("Phrase 1", "Phrase 2"), events.snapshot().sources)
        assertEquals(20L, events.snapshot().usage.inputAudio)
        assertEquals(2L, events.snapshot().usage.outputText)
    }

    @Test fun missingOrInconsistentUsageIsNeverFreeOrComplete() {
        val events = EvaluationEvents()
        events.accept("""{"type":"response.done","response":{"id":"r","status":"completed"}}""", 10)
        assertFalse(events.snapshot().usageComplete)
        events.accept("""{"type":"response.done","response":{"id":"r2","status":"completed","usage":{"input_tokens":99,"output_tokens":3,"input_tokens_details":{"audio_tokens":10},"output_tokens_details":{"text_tokens":3}}}}""", 20)
        assertFalse(events.snapshot().usageComplete)
    }

    @Test fun audioOrToolOutputAndFailedResponsesAreExplicitFailures() {
        val events = EvaluationEvents()
        events.accept("""{"type":"response.audio.delta","delta":"not-to-decode"}""", 10)
        assertEquals("UNEXPECTED_AUDIO", events.snapshot().error)
        val failed = EvaluationEvents()
        failed.accept("""{"type":"response.done","response":{"id":"r","status":"failed"}}""", 10)
        assertEquals("RESPONSE_FAILED", failed.snapshot().error)
    }
}
