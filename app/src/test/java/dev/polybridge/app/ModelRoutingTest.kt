package dev.polybridge.app

import dev.polybridge.core.*
import dev.polybridge.qwen.*
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
class ModelRoutingTest {
    @Test fun recordedReplyUsesItsStartSnapshotAndPersistsItsActualOrigin() {
        val store = ProbeStore(RuntimeEnvironment.getApplication(), "model-routing")
        val options = ModelSelection(ModelPreset.AUDIO31).options(ModelFeature.REPLY, QwenOptions())
        val request = requireNotNull(store.begin(Kind.HOLD, PlaybackTarget.SELF, null, Side.CHINESE, null,
            modelOptions = options, feature = ModelFeature.REPLY))
        var used: QwenOptions? = null
        val cloud = object : SpeechTranslation, UtteranceTranslation {
            override fun transcribe(side: Side, wave: ByteArray): String = error("Must not use old ASR")
            override fun translate(side: Side, source: String): String = error("Must not use old MT")
            override fun utterance(side: Side, wave: ByteArray, audio: Boolean, sourceReady: (String) -> Unit): SpeechResult {
                sourceReady("你好")
                return SpeechResult("你好", "Bonjour", model = ModelTrace(ModelIds.AUDIO31, ModelIds.AUDIO31))
            }
        }
        val translator = ConversationTranslator(store, CoroutineScope(Dispatchers.Unconfined),
            configuredProvider = { used = it; cloud }) { error("Must not reread current configuration") }
        store.finish(request.empty(Verdict.SIGNAL))
        translator.recorded(request.id, byteArrayOf(1), request.modelOptions)
        val turn = store.chat.value.turns.single()
        assertEquals(options, used)
        assertEquals(TurnStatus.READY, turn.status)
        assertEquals(ModelFeature.REPLY.name, turn.feature)
        assertEquals(ModelIds.AUDIO31, turn.model?.actualModel)
        assertEquals(ModelIds.AUDIO31, ProbeStore(RuntimeEnvironment.getApplication(), "model-routing").chat.value.turns.single().model?.actualModel)
    }
}
