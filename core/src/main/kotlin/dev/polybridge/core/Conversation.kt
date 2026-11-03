package dev.polybridge.core

import java.util.UUID

enum class Side(val source: String, val target: String) {
    CHINESE("zh-CN", "fr-FR"), FRENCH("fr-FR", "zh-CN")
}

enum class TurnStatus { CAPTURING, TRANSCRIBING, TRANSLATING, READY, DEMO_READY, FAILED }

data class Turn(val id: String, val side: Side, val status: TurnStatus = TurnStatus.CAPTURING,
    val sourceText: String = "", val translatedText: String = "", val verdict: Verdict? = null,
    val issue: TranslationIssue? = null, val atMs: Long = 0, val session: String? = null)

data class Conversation(val turns: List<Turn> = emptyList(), val session: String? = null) {
    val currentTurns: List<Turn> get() = if (session == null) emptyList() else turns.filter { it.session == session }

    fun newSession() = copy(session = UUID.randomUUID().toString())
    fun press(id: String, side: Side): Conversation {
        if (turns.any { it.id == id || it.status == TurnStatus.CAPTURING }) return this
        return append(Turn(id, side, atMs = System.currentTimeMillis()))
    }

    fun typed(id: String, side: Side, text: String): Conversation {
        if (text.isBlank() || turns.any { it.id == id }) return this
        return append(Turn(id, side, TurnStatus.TRANSLATING, text.trim(), atMs = System.currentTimeMillis()))
    }

    fun importing(id: String, side: Side): Conversation =
        if (turns.any { it.id == id }) this else append(Turn(id, side, TurnStatus.TRANSCRIBING, atMs = System.currentTimeMillis()))

    fun cancel(id: String) = copy(turns = turns.filterNot { it.id == id && it.status == TurnStatus.CAPTURING })

    fun captureEnded(id: String, verdict: Verdict): Conversation {
        if (verdict == Verdict.CANCELLED) return cancel(id)
        return change(id) {
            if (it.status != TurnStatus.CAPTURING) it
            else it.copy(status = if (verdict == Verdict.SIGNAL) TurnStatus.TRANSCRIBING else TurnStatus.FAILED, verdict = verdict)
        }
    }

    fun transcribed(id: String, source: String) = change(id) {
        if (it.status != TurnStatus.TRANSCRIBING || source.isBlank()) it
        else it.copy(status = TurnStatus.TRANSLATING, sourceText = source.trim())
    }

    fun translated(id: String, expectedSource: String, result: String) = change(id) {
        if (it.status != TurnStatus.TRANSLATING || it.sourceText != expectedSource || result.isBlank()) it
        else it.copy(status = TurnStatus.READY, translatedText = result.trim(), issue = null)
    }

    fun failed(id: String, issue: TranslationIssue) = change(id) {
        if (it.status == TurnStatus.TRANSCRIBING || it.status == TurnStatus.TRANSLATING) it.copy(status = TurnStatus.FAILED, issue = issue) else it
    }

    fun retranslate(id: String, source: String) = change(id) {
        if (source.isBlank() || it.status !in setOf(TurnStatus.READY, TurnStatus.DEMO_READY, TurnStatus.FAILED)) it
        else it.copy(status = TurnStatus.TRANSLATING, sourceText = source.trim(), issue = null)
    }

    fun editTranslation(id: String, text: String) = change(id) {
        if (it.status == TurnStatus.READY && text.isNotBlank()) it.copy(translatedText = text.trim()) else it
    }

    fun correct(id: String, source: String, target: String) = change(id) {
        if (source.isBlank() || target.isBlank() || it.status !in setOf(TurnStatus.READY, TurnStatus.DEMO_READY, TurnStatus.FAILED)) it
        else it.copy(status = TurnStatus.READY, sourceText = source.trim(), translatedText = target.trim(), issue = null)
    }

    fun restoreHistory(previous: Conversation): Conversation {
        val ids = turns.mapTo(mutableSetOf()) { it.id }
        val restored = previous.recoverInterrupted().turns.filterNot { it.id in ids }
        return copy(turns = (restored + turns).takeLast(100), session = if (turns.isEmpty()) previous.session else session)
    }

    fun recoverInterrupted() = copy(turns = turns.map {
        when (it.status) {
            TurnStatus.CAPTURING -> it.copy(status = TurnStatus.FAILED, verdict = Verdict.INTERRUPTED)
            TurnStatus.TRANSCRIBING, TurnStatus.TRANSLATING -> it.copy(status = TurnStatus.FAILED, issue = TranslationIssue.INTERRUPTED)
            else -> it
        }
    })

    private fun append(turn: Turn): Conversation {
        val current = session ?: UUID.randomUUID().toString()
        return copy(session = current, turns = (turns + turn.copy(session = current)).takeLast(100))
    }
    private fun change(id: String, update: (Turn) -> Turn) = copy(turns = turns.map { if (it.id == id) update(it) else it })
}
