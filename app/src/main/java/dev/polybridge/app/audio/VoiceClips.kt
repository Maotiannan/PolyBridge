package dev.polybridge.app.audio

import dev.polybridge.core.Turn
import dev.polybridge.core.fingerprintHash
import dev.polybridge.qwen.QwenOptions

/** Session-only audio. Include the visible translation so a correction can never replay an older take. */
internal class VoiceClips(private val byteLimit: Int = 8 * 1024 * 1024) {
    internal data class Key(val id: String, val language: String, val text: String, val voice: String, val model: String, val configuration: String = "")
    internal data class Cached(val wave: ByteArray, val model: String)
    fun key(turn: Turn, options: QwenOptions) = Key(turn.id, turn.side.target, turn.translatedText, options.voice, options.voiceModel,
        fingerprintHash(options.region.name + "|" + options.workspace))
    private fun generatedKey(turn: Turn, options: QwenOptions): Key? {
        val model = turn.model ?: return null
        val selected = if (turn.live == null) options.utteranceModel else options.liveModel
        if (model.requestedModel != selected) return null
        return Key(turn.id, turn.side.target, turn.translatedText, model.voice.orEmpty(), selected,
            fingerprintHash(listOf(options.region.name, options.workspace, selected, options.asrModel,
                options.translationModel, options.voiceModel, options.voice).joinToString("|")))
    }
    fun putGenerated(turn: Turn, options: QwenOptions, wave: ByteArray) { generatedKey(turn, options)?.let { put(it, wave) } }
    fun find(turn: Turn, options: QwenOptions): Cached? {
        generatedKey(turn, options)?.let { key -> get(key)?.let { return Cached(it, turn.model?.actualModel ?: key.model) } }
        return get(key(turn, options))?.let { Cached(it, options.voiceModel) }
    }
    private val clips = LinkedHashMap<Key, ByteArray>(16, .75f, true)
    private var size = 0
    @Synchronized operator fun get(key: Key): ByteArray? = clips[key]
    @Synchronized fun put(key: Key, wave: ByteArray) {
        if (wave.isEmpty() || wave.size > byteLimit) return
        size -= clips.put(key, wave)?.size ?: 0
        size += wave.size
        val iterator = clips.entries.iterator()
        while (size > byteLimit && iterator.hasNext()) { size -= iterator.next().value.size; iterator.remove() }
    }
    @Synchronized fun clear() { clips.clear(); size = 0 }
}
