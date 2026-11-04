package dev.polybridge.core

/** Screen-caption publication boundary; protocol previews remain available to other modes. */
class StableCaptions(private val update: (LiveUpdate) -> Unit) {
    private val pending = linkedMapOf<Pair<Side, String>, LiveUpdate.Caption>()
    private val completed = linkedSetOf<Pair<Side, String>>()

    @Synchronized fun accept(event: LiveUpdate) {
        when (event) {
            is LiveUpdate.Caption -> {
                val key = event.side to event.id
                if (key in completed || pending[key]?.sourceFinal == true) return
                // An error marker is not a transcript. Keep the recovery state visible without
                // publishing the unreliable hypothesis, and allow its later withdrawal.
                val source = if (event.sourceFinal && event.source.isNotBlank()) event.copy(target = "", issue = null)
                    else if (event.issue == TranslationIssue.UNRELIABLE && key !in pending)
                        event.copy(source = "", target = "", sourceFinal = false)
                    else return
                if (key !in pending && pending.size >= 32) finishPending(pending.values.first(), TranslationIssue.LIMIT)
                pending[key] = source
                update(source)
            }
            is LiveUpdate.Finished -> {
                val key = event.side to event.id
                if (key in completed) return
                val original = pending.remove(key)?.takeIf { it.sourceFinal }
                val conflict = original != null && (!event.sourceFinal || original.source != event.source)
                val issue = if (conflict || !event.sourceFinal) TranslationIssue.UNRELIABLE else event.issue
                    ?: TranslationIssue.EMPTY_REPLY.takeIf { event.target.isBlank() }
                remember(key)
                update(event.copy(source = original?.source ?: event.source.takeIf { event.sourceFinal }.orEmpty(),
                    target = if (issue == null) event.target else "", pcm = byteArrayOf(), issue = issue))
            }
            is LiveUpdate.Withdrawn -> {
                val key = event.side to event.id
                if (pending[key]?.sourceFinal == false) { pending.remove(key); update(event) }
                // Already-read original text cannot disappear.
            }
            is LiveUpdate.Reconnecting -> {
                pending.values.toList().forEach { finishPending(it, TranslationIssue.NETWORK) }
                update(event)
            }
            LiveUpdate.Reconnected -> { pending.clear(); completed.clear(); update(event) }
            is LiveUpdate.Stopped -> {
                pending.values.toList().forEach { finishPending(it, event.issue) }
                update(event)
            }
        }
    }

    private fun finishPending(line: LiveUpdate.Caption, issue: TranslationIssue) {
        accept(LiveUpdate.Finished(line.id, line.side, line.startedMs, line.source, "", byteArrayOf(), issue,
            sourceFinal = line.sourceFinal))
    }

    private fun remember(key: Pair<Side, String>) {
        completed += key
        if (completed.size > 512) completed.remove(completed.first())
    }
}
