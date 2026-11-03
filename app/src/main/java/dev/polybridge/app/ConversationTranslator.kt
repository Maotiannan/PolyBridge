package dev.polybridge.app

import dev.polybridge.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConversationTranslator(private val store: ProbeStore, private val scope: CoroutineScope,
    private val provider: () -> SpeechTranslation?) {
    private val spoken = MutableSharedFlow<Turn>(extraBufferCapacity = 8)
    val newTranslations = spoken.asSharedFlow()
    private val recognizing = ConcurrentHashMap.newKeySet<String>()

    fun recorded(id: String, wave: ByteArray?) {
        val turn = store.chat.value.turns.find { it.id == id && it.status == TurnStatus.TRANSCRIBING } ?: return
        if (wave == null) { store.changeChat { it.failed(id, TranslationIssue.EMPTY_REPLY) }; return }
        if (!recognizing.add(id)) return
        run(id, onFinished = { recognizing.remove(id) }) { api ->
            if (store.chat.value.turns.none { it.id == id && it.status == TurnStatus.TRANSCRIBING }) return@run
            val source = retryNetworkOnce { api.transcribe(turn.side, wave) }
            store.changeChat { it.transcribed(id, source) }
            translate(api, id)
        }
    }

    fun typed(side: Side, text: String): String {
        val id = UUID.randomUUID().toString()
        store.changeChat { it.typed(id, side, text) }
        run(id) { translate(it, id) }
        return id
    }

    fun imported(side: Side, wave: ByteArray): String {
        val id = UUID.randomUUID().toString()
        store.changeChat { it.importing(id, side) }
        recorded(id, wave)
        return id
    }

    fun retry(id: String, source: String) {
        val changed = store.changeChat { it.retranslate(id, source) }
        if (changed) run(id) { translate(it, id) }
    }

    private fun translate(api: SpeechTranslation, id: String) {
        val turn = store.chat.value.turns.find { it.id == id && it.status == TurnStatus.TRANSLATING } ?: return
        val target = retryNetworkOnce { api.translate(turn.side, turn.sourceText) }
        if (store.changeChat { it.translated(id, turn.sourceText, target) })
            store.chat.value.turns.find { it.id == id && it.status == TurnStatus.READY }?.let(spoken::tryEmit)
    }

    private fun <T> retryNetworkOnce(call: () -> T): T = try { call() } catch (error: TranslationFailure) {
        if (error.issue != TranslationIssue.NETWORK) throw error
        call()
    }

    private fun run(id: String, onFinished: () -> Unit = {}, task: (SpeechTranslation) -> Unit) {
        scope.launch {
            try {
                val api = runCatching(provider).getOrNull() ?: throw TranslationFailure(TranslationIssue.CREDENTIAL)
                task(api)
            } catch (failure: TranslationFailure) {
                store.changeChat { it.failed(id, failure.issue) }
            } catch (_: RuntimeException) {
                store.changeChat { it.failed(id, TranslationIssue.SERVICE) }
            } finally { onFinished() }
        }
    }
}
