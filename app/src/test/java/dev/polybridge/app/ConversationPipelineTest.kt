package dev.polybridge.app

import android.content.Context
import dev.polybridge.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationPipelineTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store get() = ProbeStore(context)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun twentyAlternatingReleasesPassTheirOwnWordsAndPersistExactlyOnce() {
        val session = store
        var sent = 0
        val provider = object : SpeechTranslation {
            override fun transcribe(side: Side, wave: ByteArray) = "${side.source} ${wave.single()}"
            override fun translate(side: Side, source: String): String { sent++; return "$source / ${side.target}" }
        }
        val pipeline = ConversationTranslator(session, scope) { provider }
        repeat(20) { i ->
            val side = Side.entries[i % 2]
            val request = session.begin(Kind.HOLD, PlaybackTarget.SELF, null, side, "fixture")!!
            session.finish(request.empty(Verdict.SIGNAL))
            pipeline.recorded(request.id, byteArrayOf(i.toByte()))
            pipeline.recorded(request.id, byteArrayOf(i.toByte()))
        }
        assertEquals(20, sent)
        val disk = store.chat.value.turns
        assertEquals(20, disk.size)
        disk.forEachIndexed { i, turn ->
            assertEquals(TurnStatus.READY, turn.status)
            assertEquals("${turn.side.source} $i / ${turn.side.target}", turn.translatedText)
        }
    }

    @Test fun errorAfterRecognitionRetainsTextAndAllowsCorrectedRetry() {
        val session = store
        val failing = object : SpeechTranslation {
            override fun transcribe(side: Side, wave: ByteArray) = "deux places"
            override fun translate(side: Side, source: String): String = throw TranslationFailure(TranslationIssue.NETWORK)
        }
        val pipeline = ConversationTranslator(session, scope) { failing }
        val r = session.begin(Kind.HOLD, PlaybackTarget.SELF, null, Side.FRENCH, "test")!!
        session.finish(r.empty(Verdict.SIGNAL))
        pipeline.recorded(r.id, byteArrayOf(1))
        assertEquals("deux places", store.chat.value.turns.single().sourceText)
        assertEquals(TranslationIssue.NETWORK, store.chat.value.turns.single().issue)
        val retry = object : SpeechTranslation by failing {
            override fun translate(side: Side, source: String) = "三个座位"
        }
        ConversationTranslator(session, scope) { retry }.retry(r.id, "trois places")
        val fixed = store.chat.value.turns.single()
        assertEquals("三个座位", fixed.translatedText)
        assertEquals("trois places", fixed.sourceText)
    }

    @Test fun aTemporaryNetworkDropRetriesOnlyTheInterruptedStage() {
        var asr = 0
        var mt = 0
        val api = object : SpeechTranslation {
            override fun transcribe(side: Side, wave: ByteArray): String { asr++; return "Bonjour." }
            override fun translate(side: Side, source: String): String {
                if (++mt == 1) throw TranslationFailure(TranslationIssue.NETWORK)
                return "你好。"
            }
        }
        val session = store
        val r = session.begin(Kind.HOLD, PlaybackTarget.SELF, null, Side.FRENCH, "test")!!
        session.finish(r.empty(Verdict.SIGNAL))
        ConversationTranslator(session, scope) { api }.recorded(r.id, byteArrayOf(1))
        assertEquals(1, asr)
        assertEquals(2, mt)
        assertEquals(TurnStatus.READY, session.chat.value.turns.single().status)
    }
}
