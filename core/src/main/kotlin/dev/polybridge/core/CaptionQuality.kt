package dev.polybridge.core

import java.util.Locale

data class RepetitionPolicy(val minimumCycles: Int = 8, val minimumCharacters: Int = 128, val maximumUnit: Int = 96)

/** Detect sustained mechanical loops, not ordinary emphasis or repeated short replies.
 * A match asks for an independent audio check; it is never silently rewritten as a valid sentence. */
object CaptionQuality {
    fun repetitive(text: String, policy: RepetitionPolicy = RepetitionPolicy()): Boolean {
        if (text.length < policy.minimumCharacters) return false
        val letters = text.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        if (letters.length < policy.minimumCharacters) return false
        for (period in 1..minOf(policy.maximumUnit, letters.length / policy.minimumCycles)) {
            var matched = 0
            for (i in period until letters.length) {
                matched = if (letters[i] == letters[i - period]) matched + 1 else 0
                if (matched >= period * (policy.minimumCycles - 1) && matched + period >= policy.minimumCharacters) return true
            }
        }
        return false
    }
}
