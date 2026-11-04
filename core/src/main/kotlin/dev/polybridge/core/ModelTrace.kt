package dev.polybridge.core

/** Safe provenance only: no credentials, host account identifiers or recorded audio. */
data class ModelTrace(val requestedModel: String, val actualModel: String? = null,
    val sessionId: String? = null, val requestId: String? = null, val feature: String? = null,
    val voice: String? = null, val configuration: String? = null, val atMs: Long = System.currentTimeMillis(),
    val recoveredBy: String? = null, val recognition: ModelTrace? = null)

data class SpeechResult(val source: String, val target: String, val wave: ByteArray = byteArrayOf(), val model: ModelTrace? = null)
interface UtteranceTranslation {
    fun utterance(side: Side, wave: ByteArray, audio: Boolean = false, sourceReady: (String) -> Unit = {}): SpeechResult
}
