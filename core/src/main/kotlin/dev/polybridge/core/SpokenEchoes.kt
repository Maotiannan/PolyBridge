package dev.polybridge.core

import java.text.Normalizer
import java.util.Locale

/** Keep a receipt only for audio actually heard on a route. Times use the input PCM clock used by provider VAD. */
class SpokenEchoes {
    private data class Receipt(val language: String, val words: String, val start: Long, var end: Long?)
    private val recent = ArrayDeque<Receipt>()
    private fun words(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    @Synchronized fun start(language: String, text: String, inputMs: Long) {
        finish(inputMs)
        recent.addLast(Receipt(language, words(text), inputMs, null))
        while (recent.size > 4) recent.removeFirst()
    }

    @Synchronized fun finish(inputMs: Long) { recent.lastOrNull()?.let { if (it.end == null) it.end = inputMs } }
    @Synchronized fun reset() = recent.clear()

    @Synchronized fun independent(side: Side, source: String, startMs: Long): Boolean {
        val current = recent.lastOrNull()?.takeIf { it.end == null } ?: return false
        if (startMs > 0 && startMs + 320 < current.start) return false
        val heard = words(source)
        if (heard.length < (if (side == Side.CHINESE) 3 else 7)) return false
        if (side.source != current.language) return true
        if (current.words.contains(heard) || heard.contains(current.words)) return false
        // ASR may briefly revise a prefix. Wait for two non-overlapping fragments of the new phrase.
        if (heard.length < 8) return false
        return heard.chunked(3).count { it.length == 3 && it !in current.words } >= 2
    }

    @Synchronized fun repeated(side: Side, source: String, startMs: Long, inputMs: Long): Boolean {
        val heard = words(source)
        if (heard.isEmpty()) return false
        return recent.any { old ->
            old.language == side.source && old.words == heard &&
                (startMs in (old.start - 320)..((old.end ?: inputMs) + 210) ||
                    (startMs == 0L && inputMs - (old.end ?: inputMs) <= 1600))
        }
    }
}
