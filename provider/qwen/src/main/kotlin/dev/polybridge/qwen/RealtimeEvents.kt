package dev.polybridge.qwen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.polybridge.core.*
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Recognition items and response items have independent lifetimes and IDs. */
internal class RealtimeEvents(private val side: Side?, private val update: (LiveUpdate) -> Unit) {
    var inferLanguage = false
    var requireItemAssociation = false
    val idle: Boolean @Synchronized get() = inputs.isEmpty() && outputs.isEmpty()

    private class Sentence(val id: String, val start: Long, val exactStart: Boolean) {
        var language = ""
        var source = ""
        var safeSource = ""
        var end: Long? = null
        var sourceDone = false
        var reply: Reply? = null
        var previewShown = false
        var previewSide: Side? = null
    }
    private class Reply(val id: String) {
        var item = ""
        var target = ""
        var targetDone = false
        var done = false
        var issue: TranslationIssue? = null
        var audio: ByteArrayOutputStream? = ByteArrayOutputStream()
    }

    private val inputs = linkedMapOf<String, Sentence>()
    private val outputs = linkedMapOf<String, Reply>()
    private val itemInputs = linkedMapOf<String, String>()
    private val completedInputs = linkedSetOf<String>()
    private val completedOutputs = linkedSetOf<String>()
    private var latestStart = -1L
    private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun input(id: String, start: Long? = null): Sentence = inputs.getOrPut(id) {
        if (inputs.size >= 12) throw TranslationFailure(TranslationIssue.LIMIT)
        val position = start?.coerceAtLeast(0) ?: (latestStart + 1).coerceAtLeast(0)
        latestStart = maxOf(latestStart, position)
        Sentence(id, position, start != null)
    }
    private fun reply(id: String): Reply? {
        if (id.isBlank() || id in completedOutputs) return null
        return outputs.getOrPut(id) {
            if (outputs.size >= 12) throw TranslationFailure(TranslationIssue.LIMIT)
            Reply(id)
        }
    }
    private fun output(event: JsonObject): Reply? = reply(event.string("response_id"))?.also {
        event.string("item_id").takeIf(String::isNotBlank)?.let { id -> it.item = id }
    }

    @Synchronized fun accept(raw: String) {
        val event = JsonParser.parseString(raw).asJsonObject
        val type = event.string("type")
        when (type) {
            "input_audio_buffer.speech_started", "input_audio_buffer.committed" -> {
                val id = event.string("item_id")
                if (id.isBlank() || id in completedInputs) return
                input(id, event["audio_start_ms"]?.asLong)
            }
            "input_audio_buffer.speech_stopped" -> inputs[event.string("item_id")]?.end = event["audio_end_ms"]?.asLong
            "conversation.item.created" -> {
                val item = event.getAsJsonObject("item") ?: return
                val id = item.string("id")
                val previous = event.string("previous_item_id")
                if (item.string("role") == "assistant" && id.isNotBlank() && previous.isNotBlank()) {
                    itemInputs[id] = previous
                    if (itemInputs.size > 256) itemInputs.remove(itemInputs.keys.first())
                }
            }
            "conversation.item.input_audio_transcription.text", "conversation.item.input_audio_transcription.delta", "conversation.item.input_audio_transcription.completed" -> {
                val id = event.string("item_id")
                if (id.isBlank() || id in completedInputs) return
                val line = input(id)
                if (line.sourceDone) return
                event.string("language").substringBefore('-').takeIf(String::isNotBlank)?.let { line.language = it }
                line.sourceDone = type.endsWith(".completed")
                line.source = when {
                    line.sourceDone -> event.string("transcript").ifBlank { event.string("text") }.ifBlank { line.source }
                    type.endsWith(".delta") && event.has("delta") -> line.source + event.string("delta")
                    else -> event.string("text") + event.string("stash")
                }
                if (line.source.length > 32000) throw TranslationFailure(TranslationIssue.LIMIT)
            }
            "conversation.item.input_audio_transcription.failed" -> {
                inputs[event.string("item_id")]?.let { discard(it, keepUnlinkedReply = requireItemAssociation) }
                return
            }
            "response.created" -> reply(event.getAsJsonObject("response").string("id"))
            "response.audio_transcript.text", "response.audio_transcript.delta", "response.audio_transcript.done", "response.text.text", "response.text.delta", "response.text.done" -> {
                val response = output(event) ?: return
                if (response.targetDone) return
                response.targetDone = type.endsWith(".done")
                response.target = when {
                    response.targetDone -> event.string("transcript").ifBlank { event.string("text") }.ifBlank { response.target }
                    type.endsWith(".delta") -> response.target + event.string("delta")
                    else -> event.string("text") + event.string("stash")
                }
                if (response.target.length > 32000) throw TranslationFailure(TranslationIssue.LIMIT)
            }
            "response.audio.delta" -> {
                val response = output(event) ?: return
                val audio = response.audio ?: return
                val data = Base64.getDecoder().decode(event.string("delta"))
                if (outputs.values.sumOf { it.audio?.size() ?: 0 } + data.size > 4 * 1024 * 1024) response.audio = null
                else audio.write(data)
            }
            "response.done" -> {
                val body = event.getAsJsonObject("response")
                val response = reply(body.string("id")) ?: return
                response.done = true
                response.issue = when (body.string("status")) {
                    "completed" -> null
                    "cancelled", "incomplete" -> TranslationIssue.INTERRUPTED
                    else -> TranslationIssue.SERVICE
                }
                val items = body.getAsJsonArray("output")
                items?.firstOrNull()?.asJsonObject?.string("id")?.takeIf(String::isNotBlank)?.let { response.item = it }
                if (response.issue == null && !response.targetDone) {
                    val text = items?.flatMap { item -> item.asJsonObject.getAsJsonArray("content")?.map { content ->
                        content.asJsonObject.run { string("transcript").ifBlank { string("text") } }
                    }.orEmpty() }?.joinToString("")?.trim().orEmpty()
                    if (text.length > 32000) throw TranslationFailure(TranslationIssue.LIMIT)
                    if (text.isNotEmpty()) { response.target = text; response.targetDone = true }
                    else if (body.has("output")) response.issue = TranslationIssue.EMPTY_REPLY
                }
            }
            else -> return
        }
        associate()
        if (type == "response.audio.delta") return
        inputs.values.toList().forEach(::publish)
    }

    /** Explicit service linkage wins over arrival order, including delayed linkage metadata.
     * Old packets without item IDs retain FIFO compatibility; identified translation items wait. */
    private fun associate() {
        inputs.values.forEach { it.reply = null }
        val unbound = mutableListOf<Reply>()
        for (response in outputs.values.toList()) {
            val source = itemInputs[response.item]
            if (source != null) {
                if (source in completedInputs) { outputs.remove(response.id); remember(completedOutputs, response.id) }
                else inputs[source]?.let { line ->
                    if (line.reply != null && line.reply !== response) throw TranslationFailure(TranslationIssue.SERVICE)
                    line.reply = response
                }
            } else if (!requireItemAssociation || response.item.isBlank()) unbound += response
        }
        val available = inputs.values.filter { it.reply == null }.iterator()
        for (response in unbound) {
            if (!available.hasNext()) break
            available.next().reply = response
        }
    }

    private fun publish(line: Sentence) {
        val response = line.reply
        val target = response?.target.orEmpty()
        val language = line.language.ifBlank { if (inferLanguage) bilingualSide(line.source)?.source?.substringBefore('-').orEmpty() else "" }
        val recognizedSide = Side.entries.firstOrNull { it.source.substringBefore('-') == language }
        val selected = side ?: recognizedSide
        val recognized = selected != null && recognizedSide == selected && line.source.isNotBlank()
        val sourceLoop = CaptionQuality.repetitive(line.source)
        val targetLoop = CaptionQuality.repetitive(target)
        val wrongLanguage = inferLanguage && response?.targetDone == true && target.count { it.isLetter() } >= 4 &&
            bilingualSide(target) == selected && selected == Side.FRENCH
        val quality = TranslationIssue.UNRELIABLE.takeIf { sourceLoop || targetLoop || wrongLanguage }
        if (!sourceLoop) line.safeSource = line.source
        if (response?.done == true && line.sourceDone && (response.targetDone || response.issue != null || !recognized)) {
            if (recognized) {
                val issue = quality ?: response.issue ?: TranslationIssue.EMPTY_REPLY.takeIf { target.isBlank() }
                val pcm = if (issue == null) response.audio?.toByteArray() else null
                update(LiveUpdate.Finished(line.id, requireNotNull(selected), line.start, line.safeSource.trim(),
                    if (quality == null) target.trim() else "", pcm ?: byteArrayOf(), issue,
                    line.end.takeIf { line.exactStart }, sourceFinal = !sourceLoop))
                line.previewShown = false
            }
            discard(line)
        } else if (recognized) {
            if (line.previewSide != null && line.previewSide != selected) withdraw(line)
            line.previewSide = selected
            line.previewShown = true
            update(LiveUpdate.Caption(line.id, requireNotNull(selected), line.safeSource.trim(),
                if (quality == null) target.trim() else "", line.start, quality, sourceFinal = line.sourceDone && !sourceLoop))
        } else withdraw(line)
    }

    private fun withdraw(line: Sentence) {
        if (line.previewShown) { line.previewShown = false; line.previewSide?.let { update(LiveUpdate.Withdrawn(line.id, it)) } }
    }
    private fun discard(line: Sentence, keepUnlinkedReply: Boolean = false) {
        withdraw(line)
        inputs.remove(line.id)
        line.reply?.takeIf { !keepUnlinkedReply || itemInputs[it.item] == line.id }?.let {
            outputs.remove(it.id); remember(completedOutputs, it.id)
        }
        remember(completedInputs, line.id)
    }
    private fun remember(set: MutableSet<String>, id: String) {
        set += id
        if (set.size > 256) set.remove(set.first())
    }
}

/** Only used by explicitly bilingual models that omit language metadata; never inferred from translation. */
internal fun bilingualSide(source: String): Side? = when {
    source.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> Side.CHINESE
    source.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN && it.isLetter() } -> Side.FRENCH
    else -> null
}
