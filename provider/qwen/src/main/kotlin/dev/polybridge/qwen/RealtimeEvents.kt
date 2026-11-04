package dev.polybridge.qwen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.polybridge.core.*
import java.io.ByteArrayOutputStream
import java.util.Base64

/** The recognizer and the translator assign different IDs to a spoken turn. Each one has its own socket. */
internal class RealtimeEvents(private val side: Side?, private val update: (LiveUpdate) -> Unit) {
    var inferLanguage = false
    val idle: Boolean @Synchronized get() = inputs.isEmpty()
    private class Sentence(val id: String, val start: Long) {
        var response = ""
        var language = ""
        var source = ""
        var target = ""
        var safeSource = ""
        var end: Long? = null
        var sourceDone = false
        var targetDone = false
        var responseDone = false
        var issue: TranslationIssue? = null
        var previewShown = false
        var previewSide: Side? = null
        var audio: ByteArrayOutputStream? = ByteArrayOutputStream()
    }

    private val inputs = linkedMapOf<String, Sentence>()
    private val outputs = mutableMapOf<String, Sentence>()
    private val completedInputs = linkedSetOf<String>()
    private val completedOutputs = linkedSetOf<String>()
    private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private val sourceLanguage = side?.source?.substringBefore('-')

    @Synchronized fun accept(raw: String) {
        val event = JsonParser.parseString(raw).asJsonObject
        val type = event.string("type")
        when (type) {
            "input_audio_buffer.speech_started", "input_audio_buffer.committed" -> {
                val id = event.string("item_id")
                if (id.isBlank() || id in completedInputs) return
                if (inputs.size >= 12) throw TranslationFailure(TranslationIssue.SERVICE)
                inputs.putIfAbsent(id, Sentence(id, event["audio_start_ms"]?.asLong ?: 0))
            }
            "input_audio_buffer.speech_stopped" -> {
                inputs[event.string("item_id")]?.end = event["audio_end_ms"]?.asLong
            }
            "conversation.item.input_audio_transcription.text", "conversation.item.input_audio_transcription.delta", "conversation.item.input_audio_transcription.completed" -> {
                val id = event.string("item_id").takeIf { it.isNotBlank() } ?: return
                if (id in completedInputs) return
                val line = inputs.getOrPut(id) {
                    if (inputs.size >= 12) throw TranslationFailure(TranslationIssue.LIMIT)
                    Sentence(id, 0)
                }
                if (line.sourceDone) return
                event.string("language").substringBefore('-').takeIf { it.isNotBlank() }?.let { line.language = it }
                line.sourceDone = type.endsWith(".completed")
                line.source = when {
                    line.sourceDone -> event.string("transcript").ifBlank { event.string("text") }.ifBlank { line.source }
                    type.endsWith(".delta") && event.has("delta") -> line.source + event.string("delta")
                    else -> event.string("text") + event.string("stash")
                }
                if (line.source.length > 32000) throw TranslationFailure(TranslationIssue.LIMIT)
                publish(line)
            }
            "conversation.item.input_audio_transcription.failed" -> inputs[event.string("item_id")]?.let(::discard)
            "response.created" -> {
                val id = event.getAsJsonObject("response").string("id")
                if (id.isBlank() || id in outputs || id in completedOutputs) return
                val line = inputs.values.firstOrNull { it.response.isEmpty() } ?: return
                line.response = id
                outputs[line.response] = line
            }
            "response.audio_transcript.text", "response.audio_transcript.delta", "response.audio_transcript.done", "response.text.text", "response.text.delta", "response.text.done" -> {
                val line = outputs[event.string("response_id")] ?: return
                if (line.targetDone) return
                line.targetDone = type.endsWith(".done")
                line.target = when {
                    line.targetDone -> event.string("transcript").ifBlank { event.string("text") }.ifBlank { line.target }
                    type.endsWith(".delta") -> line.target + event.string("delta")
                    else -> event.string("text") + event.string("stash")
                }
                if (line.target.length > 32000) throw TranslationFailure(TranslationIssue.LIMIT)
                publish(line)
            }
            "response.audio.delta" -> {
                val line = outputs[event.string("response_id")] ?: return
                if (side != null && line.language.isNotBlank() && line.language != sourceLanguage) return
                val audio = line.audio ?: return
                val data = Base64.getDecoder().decode(event.string("delta"))
                if (audio.size() + data.size > 4 * 1024 * 1024) line.audio = null
                else audio.write(data)
            }
            "response.done" -> {
                val response = event.getAsJsonObject("response")
                val line = outputs[response.string("id")] ?: return
                line.responseDone = true
                when (response.string("status")) {
                    "completed" -> Unit
                    "cancelled", "incomplete" -> line.issue = TranslationIssue.INTERRUPTED
                    else -> line.issue = TranslationIssue.SERVICE
                }
                if (line.issue == null && !line.targetDone) {
                    val text = response.getAsJsonArray("output")?.flatMap { item ->
                        item.asJsonObject.getAsJsonArray("content")?.map { content ->
                            content.asJsonObject.run { string("transcript").ifBlank { string("text") } }
                        }.orEmpty()
                    }?.joinToString("")?.trim().orEmpty()
                    if (text.isNotEmpty()) { line.target = text; line.targetDone = true }
                }
                publish(line)
            }
        }
    }

    private fun publish(line: Sentence) {
        val language = line.language.ifBlank { if (inferLanguage) bilingualSide(line.source)?.source?.substringBefore('-').orEmpty() else "" }
        val recognizedSide = Side.entries.firstOrNull { it.source.substringBefore('-') == language }
        val selected = side ?: recognizedSide
        val recognized = selected != null && recognizedSide == selected && line.source.isNotBlank()
        val sourceLoop = CaptionQuality.repetitive(line.source)
        val targetLoop = CaptionQuality.repetitive(line.target)
        val wrongLanguage = inferLanguage && line.targetDone && line.target.count { it.isLetter() } >= 4 &&
            bilingualSide(line.target) == selected && selected == Side.FRENCH
        val quality = TranslationIssue.UNRELIABLE.takeIf { sourceLoop || targetLoop || wrongLanguage }
        if (!sourceLoop) line.safeSource = line.source
        if (line.responseDone && line.sourceDone && (line.targetDone || line.issue != null || !recognized)) {
            if (recognized) {
                val issue = quality ?: line.issue ?: TranslationIssue.EMPTY_REPLY.takeIf { line.target.isBlank() }
                val pcm = if (issue == null) line.audio?.toByteArray() else null
                update(LiveUpdate.Finished(line.id, requireNotNull(selected), line.start, line.safeSource.trim(),
                    if (quality == null) line.target.trim() else "", pcm ?: byteArrayOf(), issue, line.end))
                line.previewShown = false
            }
            discard(line)
        } else if (recognized) {
            line.previewShown = true
            if (line.previewSide != null && line.previewSide != selected) withdraw(line)
            line.previewSide = selected
            line.previewShown = true
            update(LiveUpdate.Caption(line.id, requireNotNull(selected), line.safeSource.trim(),
                if (quality == null) line.target.trim() else "", line.start, quality))
        } else withdraw(line)
    }

    private fun withdraw(line: Sentence) {
        if (line.previewShown) { line.previewShown = false; line.previewSide?.let { update(LiveUpdate.Withdrawn(line.id, it)) } }
    }
    private fun discard(line: Sentence) {
        withdraw(line); inputs.remove(line.id); outputs.remove(line.response)
        completedInputs += line.id
        if (line.response.isNotBlank()) completedOutputs += line.response
        if (completedInputs.size > 256) completedInputs.remove(completedInputs.first())
        if (completedOutputs.size > 256) completedOutputs.remove(completedOutputs.first())
    }
}

/** Only used by explicitly bilingual models that omit language metadata; never inferred from translation. */
internal fun bilingualSide(source: String): Side? = when {
    source.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> Side.CHINESE
    source.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN && it.isLetter() } -> Side.FRENCH
    else -> null
}
