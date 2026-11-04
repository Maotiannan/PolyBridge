package dev.polybridge.app

import dev.polybridge.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import dev.polybridge.qwen.QwenOptions
import dev.polybridge.qwen.QwenSpeech
import dev.polybridge.qwen.ModelIds

class ConversationTranslator(private val store: ProbeStore, private val scope: CoroutineScope,
    private val configuredProvider: ((QwenOptions) -> SpeechTranslation?)? = null,
    private val generated: (Turn, ByteArray, QwenOptions) -> Unit = { _, _, _ -> },
    private val provider: () -> SpeechTranslation?) {
    private val spoken = MutableSharedFlow<Turn>(extraBufferCapacity = 8)
    val newTranslations = spoken.asSharedFlow()
    private val recognizing = ConcurrentHashMap.newKeySet<String>()

    fun recorded(id: String, wave: ByteArray?, options: QwenOptions? = null, audio: Boolean = false) {
        val turn = store.chat.value.turns.find { it.id == id && it.status == TurnStatus.TRANSCRIBING } ?: return
        if (wave == null) { store.changeChat { it.failed(id, TranslationIssue.EMPTY_REPLY) }; return }
        if (!recognizing.add(id)) return
        run(id, options, onFinished = { recognizing.remove(id) }) { api ->
            if (store.chat.value.turns.none { it.id == id && it.status == TurnStatus.TRANSCRIBING }) return@run
            if (options != null && options.utteranceModel != ModelIds.PIPELINE && api is UtteranceTranslation) {
                val result = api.utterance(turn.side, wave, audio) { source -> store.changeChat { it.transcribed(id, source) } }
                ready(id, result.source, result.target, result.model, result.wave, options)
                return@run
            }
            val source = retryNetworkOnce { api.transcribe(turn.side, wave) }
            val recognition = (api as? QwenSpeech)?.lastTrace
            store.changeChat { it.transcribed(id, source) }
            translate(api, id, recognition)
        }
    }

    fun typed(side: Side, text: String): String {
        val id = UUID.randomUUID().toString()
        store.changeChat { it.typed(id, side, text) }
        run(id) { translate(it, id) }
        return id
    }

    fun retry(id: String, source: String) {
        val changed = store.changeChat { it.retranslate(id, source) }
        if (changed) run(id) { translate(it, id) }
    }

    private fun translate(api: SpeechTranslation, id: String, recognition: ModelTrace? = null) {
        val turn = store.chat.value.turns.find { it.id == id && it.status == TurnStatus.TRANSLATING } ?: return
        val target = retryNetworkOnce { api.translate(turn.side, turn.sourceText) }
        ready(id, turn.sourceText, target, (api as? QwenSpeech)?.lastTrace?.copy(recognition = recognition))
    }

    private fun ready(id: String, source: String, target: String, model: ModelTrace?, wave: ByteArray = byteArrayOf(), options: QwenOptions? = null) {
        if (store.changeChat { chat ->
            val translated = chat.translated(id, source, target)
            if (translated == chat) chat else translated.annotate(id, model = model)
        }) store.chat.value.turns.find { it.id == id && it.status == TurnStatus.READY }?.let { turn ->
            if (wave.isNotEmpty() && options != null) generated(turn, wave, options)
            spoken.tryEmit(turn)
        }
    }

    private fun <T> retryNetworkOnce(call: () -> T): T = try { call() } catch (error: TranslationFailure) {
        if (error.issue != TranslationIssue.NETWORK) throw error
        call()
    }

    private fun run(id: String, options: QwenOptions? = null, onFinished: () -> Unit = {}, task: (SpeechTranslation) -> Unit) {
        val captured = runCatching { if (options != null && configuredProvider != null) configuredProvider.invoke(options) else provider() }
        scope.launch {
            try {
                val api = captured.getOrNull() ?: throw TranslationFailure(TranslationIssue.CREDENTIAL)
                task(api)
            } catch (failure: TranslationFailure) {
                store.changeChat { it.failed(id, failure.issue) }
            } catch (_: RuntimeException) {
                store.changeChat { it.failed(id, TranslationIssue.SERVICE) }
            } finally { onFinished() }
        }
    }
}
