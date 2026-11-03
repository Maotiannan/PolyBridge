package dev.polybridge.core

sealed interface LiveUpdate {
    data class Reconnecting(val attempt: Int) : LiveUpdate
    data object Reconnected : LiveUpdate
    data class Caption(val id: String, val side: Side, val source: String, val target: String, val startedMs: Long = 0) : LiveUpdate
    data class Finished(val id: String, val side: Side, val startedMs: Long,
        val source: String, val target: String, val pcm: ByteArray) : LiveUpdate
    data class Stopped(val issue: TranslationIssue) : LiveUpdate
}

interface LiveSpeechSession : AutoCloseable {
    /** Connect both translation directions before enabling the microphone. */
    fun connect()
    fun send(samples: ShortArray, length: Int, rate: Int)
    /** Finish recognition of the last sentence and wait briefly for final text. */
    fun finish()
}
