package dev.polybridge.core

enum class Side(val source: String, val target: String) {
    CHINESE("zh-CN", "fr-FR"), FRENCH("fr-FR", "zh-CN")
}
enum class TurnStatus { CAPTURING, DEMO_READY, FAILED }
data class Turn(val id: String, val side: Side, val status: TurnStatus = TurnStatus.CAPTURING,
    val sourceText: String = "", val translatedText: String = "", val verdict: Verdict? = null)

interface BatchTranslationPipeline { fun example(side: Side): Pair<String, String> }
object DemoTranslation : BatchTranslationPipeline {
    override fun example(side: Side): Pair<String, String> = when (side) {
        Side.CHINESE -> "你好，很高兴见到你。" to "Bonjour, ravi de vous rencontrer."
        Side.FRENCH -> "Merci pour votre aide." to "谢谢你的帮助。"
    }
}

data class Conversation(val turns: List<Turn> = emptyList()) {
    fun press(id: String, side: Side): Conversation {
        if (turns.any { it.id == id || it.status == TurnStatus.CAPTURING }) return this
        return copy(turns = (turns + Turn(id, side)).takeLast(100))
    }
    fun cancel(id: String) = copy(turns = turns.filterNot { it.id == id && it.status == TurnStatus.CAPTURING })
    fun finish(id: String, verdict: Verdict, pipeline: BatchTranslationPipeline): Conversation {
        if (verdict == Verdict.CANCELLED) return cancel(id)
        return copy(turns = turns.map {
            if (it.id != id || it.status != TurnStatus.CAPTURING) it
            else if (verdict == Verdict.SIGNAL) {
                val (source, target) = pipeline.example(it.side)
                it.copy(status = TurnStatus.DEMO_READY, sourceText = source, translatedText = target, verdict = verdict)
            } else it.copy(status = TurnStatus.FAILED, verdict = verdict)
        })
    }
    fun recoverInterrupted() = copy(turns = turns.map {
        if (it.status == TurnStatus.CAPTURING) it.copy(status = TurnStatus.FAILED, verdict = Verdict.INTERRUPTED) else it
    })
}
