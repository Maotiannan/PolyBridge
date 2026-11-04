package dev.polybridge.eval

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class Usage(val inputAudio: Long = 0, val inputText: Long = 0, val outputText: Long = 0, val outputAudio: Long = 0)
data class EventSnapshot(val sources: List<String> = emptyList(), val targets: List<String> = emptyList(),
    val sourceFinals: Int = 0, val targetFinals: Int = 0, val responses: Int = 0,
    val firstSourceMs: Long? = null, val firstTargetMs: Long? = null, val lastTextMs: Long? = null,
    val usage: Usage = Usage(), val usageComplete: Boolean = false, val error: String? = null,
    val counts: Map<String, Int> = emptyMap(), val ready: Boolean = false, val finished: Boolean = false,
    val pendingResponses: Int = 0, val serverModels: List<String> = emptyList(),
    val sessionId: String? = null, val errorCategory: String? = null)
class EvaluationEvents {
    private val sources = linkedMapOf<String, String>()
    private val targets = linkedMapOf<String, String>()
    private val sourceFinals = mutableSetOf<String>()
    private val targetFinals = mutableSetOf<String>()
    private val responses = linkedMapOf<String, Usage?>()
    private val pendingResponses = mutableSetOf<String>()
    private val counts = linkedMapOf<String, Int>()
    private var asrUsage: Usage? = null
    private var firstSource: Long? = null
    private var firstTarget: Long? = null
    private var lastText: Long? = null
    private var error: String? = null
    private var ready = false
    private var finished = false
    private val serverModels = linkedSetOf<String>()
    private var sessionId: String? = null
    private var errorCategory: String? = null

    @Synchronized fun fail(reason: String) { if (error == null) error = reason }

    @Synchronized fun accept(json: String, elapsedMs: Long) {
        try {
            val event = JsonParser.parseString(json).asJsonObject
            val type = event.str("type").ifBlank { event.obj("header")?.str("event").orEmpty() }
            counts[type] = (counts[type] ?: 0) + 1
            if (type == "session.created" || type == "session.updated") {
                event.obj("session")?.let { session ->
                    session.str("model").takeIf { it.matches(Regex("(?:qwen|fun|pre-)[A-Za-z0-9._-]{1,100}")) }?.let { serverModels += it }
                    session.str("id").takeIf { it.matches(Regex("sess_[A-Za-z0-9_-]{1,100}")) }?.let { sessionId = it }
                }
            }
            val key = event.str("item_id").ifBlank { event.str("response_id") }.ifBlank { "unkeyed" }
            fun text(source: Boolean, id: String, value: String, delta: Boolean = false, final: Boolean = false) {
                val values = if (source) sources else targets
                val finals = if (source) sourceFinals else targetFinals
                if (delta && id in finals) return
                if (value.isNotEmpty()) {
                    values[id] = if (delta) values[id].orEmpty() + value else value
                    if (source && firstSource == null) firstSource = elapsedMs
                    if (!source && firstTarget == null) firstTarget = elapsedMs
                    lastText = elapsedMs
                }
                if (final) finals += id
            }
            when (type) {
                "response.created" -> {
                    val id = event.obj("response")?.str("id").orEmpty()
                    require(id.isNotBlank())
                    if (id !in responses) pendingResponses += id
                }
                "session.updated", "task-started" -> ready = true
                "session.finished", "task-finished" -> finished = true
                "error" -> {
                    val detail = event.obj("error")
                    val message = detail?.str("message").orEmpty().lowercase()
                    errorCategory = when {
                        "model" in message && listOf("not support", "not exist", "not found", "unavailable").any { it in message } -> "MODEL_UNAVAILABLE"
                        listOf("permission", "access denied", "unauthorized", "api key").any { it in message } -> "ACCESS_DENIED"
                        listOf("quota", "balance", "rate limit").any { it in message } -> "QUOTA_OR_LIMIT"
                        listOf("parameter", "invalid", "format", "sample rate").any { it in message } -> "INVALID_REQUEST"
                        listOf("timeout", "timed out").any { it in message } -> "UPSTREAM_TIMEOUT"
                        listOf("worker", "capacity", "overload").any { it in message } -> "SERVICE_CAPACITY"
                        else -> "UNCLASSIFIED"
                    }
                    fail("SERVICE_" + safeCode(detail?.str("code").orEmpty()))
                }
                "task-failed" -> fail("SERVICE_" + safeCode(event.obj("header")?.str("error_code").orEmpty()))
                "response.audio.delta" -> fail("UNEXPECTED_AUDIO")
                "response.function_call_arguments.delta", "response.function_call_arguments.done" -> fail("UNEXPECTED_TOOL")
                "conversation.item.input_audio_transcription.text" -> text(true, key, event.str("text") + event.str("stash"))
                "conversation.item.input_audio_transcription.delta" -> {
                    // Audio 3.1 names this delta but sends a text/stash snapshot; Live 3.8 sends delta.
                    if (event.has("delta")) text(true, key, event.str("delta"), delta = true)
                    else text(true, key, event.str("text") + event.str("stash"))
                }
                "conversation.item.input_audio_transcription.completed" -> text(true, key,
                    event.str("transcript").ifBlank { event.str("text") }, final = true)
                "conversation.item.input_audio_transcription.failed" -> fail("TRANSCRIPTION_FAILED")
                "response.text.text" -> text(false, key, event.str("text") + event.str("stash"))
                "response.text.delta" -> text(false, key, event.str("delta"), delta = true)
                "response.text.done" -> text(false, key, event.str("text"), final = true)
                "response.done" -> {
                    val response = requireNotNull(event.obj("response"))
                    val id = response.str("id")
                    require(id.isNotBlank())
                    pendingResponses -= id
                    val status = response.str("status")
                    if (status != "completed") fail("RESPONSE_" + safeCode(status).uppercase())
                    responses[id] = parseUsage(response.obj("usage"), false)
                    response.getAsJsonArray("output")?.forEach { item ->
                        val out = item.asJsonObject
                        if (out.str("type") == "function_call") fail("UNEXPECTED_TOOL")
                        out.getAsJsonArray("content")?.forEach { content ->
                            val part = content.asJsonObject
                            if (part.str("type") == "text") text(false, out.str("id").ifBlank { id }, part.str("text"), final = true)
                        }
                    }
                }
                "result-generated" -> {
                    val sentence = event.obj("payload")?.obj("output")?.obj("sentence")
                    if (sentence != null && sentence.get("heartbeat")?.asBoolean != true) {
                        val sentenceId = sentence.str("sentence_id").ifBlank { sentence.str("begin_time") }
                        require(sentenceId.isNotBlank())
                        text(true, sentenceId, sentence.str("text"), final = sentence.get("sentence_end")?.asBoolean == true)
                    }
                }
            }
            if (type == "result-generated" || type == "task-finished") {
                val raw = event.obj("payload")?.obj("usage") ?: event.obj("usage")
                    ?: event.obj("payload")?.obj("output")?.obj("usage")
                if (raw != null) asrUsage = parseUsage(raw, true)
            }
            if ((sources.values + targets.values).sumOf { it.length } > 32000) fail("TEXT_LIMIT")
        } catch (_: RuntimeException) { fail("INVALID_EVENT") }
    }

    @Synchronized fun snapshot(): EventSnapshot {
        val usages = if (asrUsage != null) listOf(asrUsage) else responses.values.toList()
        val sum = usages.filterNotNull().fold(Usage()) { a, b -> Usage(a.inputAudio + b.inputAudio,
            a.inputText + b.inputText, a.outputText + b.outputText, a.outputAudio + b.outputAudio) }
        return EventSnapshot(sources.values.toList(), targets.values.toList(), sourceFinals.size, targetFinals.size,
            responses.size, firstSource, firstTarget, lastText, sum,
            pendingResponses.isEmpty() && usages.isNotEmpty() && usages.all { it != null },
            error, counts.toMap(), ready, finished, pendingResponses.size, serverModels.toList(), sessionId, errorCategory)
    }

    private fun parseUsage(raw: JsonObject?, asr: Boolean): Usage? {
        raw ?: return null
        if (!raw.has("input_tokens") || !raw.has("output_tokens")) return null
        val input = raw.get("input_tokens").asLong
        val output = raw.get("output_tokens").asLong
        if (input < 0 || output < 0) return null
        if (asr) return Usage(inputAudio = input, outputText = output)
        val ins = raw.obj("input_tokens_details") ?: return null
        val outs = raw.obj("output_tokens_details") ?: return null
        fun number(obj: JsonObject, key: String) = obj.get(key)?.asLong ?: 0
        val usage = Usage(number(ins, "audio_tokens"), number(ins, "text_tokens"), number(outs, "text_tokens"), number(outs, "audio_tokens"))
        if (listOf(usage.inputAudio, usage.inputText, usage.outputText, usage.outputAudio).any { it < 0 } ||
            usage.inputAudio + usage.inputText != input || usage.outputText + usage.outputAudio != output) return null
        return usage
    }
}

internal fun JsonObject.str(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun safeCode(value: String) = value.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "UNKNOWN"
