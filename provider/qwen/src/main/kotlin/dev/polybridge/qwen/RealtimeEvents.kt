package dev.polybridge.qwen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.polybridge.core.*
import java.io.ByteArrayOutputStream
import java.util.Base64

/** The recognizer and the translator assign different IDs to a spoken turn. Each one has its own socket. */
internal class RealtimeEvents(private val side: Side, private val update: (LiveUpdate) -> Unit) {
    private class Sentence(val id: String, val start: Long) {
        var response = ""
        var language = ""
        var source = ""
        var target = ""
        var sourceDone = false
        var targetDone = false
        var responseDone = false
        val audio = ByteArrayOutputStream()
    }

    private val inputs = linkedMapOf<String, Sentence>()
    private val outputs = mutableMapOf<String, Sentence>()
    private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private val sourceLanguage = side.source.substringBefore('-')

    fun accept(raw: String) {
        val event = JsonParser.parseString(raw).asJsonObject
        val type = event.string("type")
        when (type) {
            "input_audio_buffer.speech_started" -> {
                val id = event.string("item_id")
                if (inputs.size >= 12) throw TranslationFailure(TranslationIssue.SERVICE)
                inputs.putIfAbsent(id, Sentence(id, event["audio_start_ms"]?.asLong ?: 0))
            }
            "conversation.item.input_audio_transcription.text", "conversation.item.input_audio_transcription.completed" -> {
                val line = inputs[event.string("item_id")] ?: return
                line.language = event.string("language").substringBefore('-')
                line.sourceDone = type.endsWith(".completed")
                line.source = if (line.sourceDone) event.string("transcript") else event.string("text") + event.string("stash")
                publish(line)
            }
            "conversation.item.input_audio_transcription.failed" -> inputs[event.string("item_id")]?.let(::discard)
            "response.created" -> {
                val line = inputs.values.firstOrNull { it.response.isEmpty() } ?: return
                line.response = event.getAsJsonObject("response").string("id")
                outputs[line.response] = line
            }
            "response.audio_transcript.text", "response.audio_transcript.done", "response.text.text", "response.text.delta", "response.text.done" -> {
                val line = outputs[event.string("response_id")] ?: return
                line.targetDone = type.endsWith(".done")
                line.target = when {
                    line.targetDone -> event.string("transcript").ifBlank { event.string("text") }.ifBlank { line.target }
                    type.endsWith(".delta") -> line.target + event.string("delta")
                    else -> event.string("text") + event.string("stash")
                }
                publish(line)
            }
            "response.audio.delta" -> {
                val line = outputs[event.string("response_id")] ?: return
                if (line.language.isNotBlank() && line.language != sourceLanguage) return
                val data = Base64.getDecoder().decode(event.string("delta"))
                if (line.audio.size() + data.size > 4 * 1024 * 1024) throw TranslationFailure(TranslationIssue.LIMIT)
                line.audio.write(data)
            }
            "response.done" -> {
                val response = event.getAsJsonObject("response")
                val line = outputs[response.string("id")] ?: return
                if (response.string("status") != "completed") { discard(line); return }
                line.responseDone = true
                publish(line)
            }
        }
    }

    private fun publish(line: Sentence) {
        if (line.responseDone && line.sourceDone) {
            if (line.language == sourceLanguage && line.source.isNotBlank() && line.targetDone && line.target.isNotBlank()) {
                update(LiveUpdate.Finished(line.id, side, line.start, line.source.trim(), line.target.trim(), line.audio.toByteArray()))
            }
            discard(line)
        } else if (line.language == sourceLanguage && line.source.isNotBlank())
            update(LiveUpdate.Caption(line.id, side, line.source.trim(), line.target.trim(), line.start))
    }

    private fun discard(line: Sentence) { inputs.remove(line.id); outputs.remove(line.response) }
}
