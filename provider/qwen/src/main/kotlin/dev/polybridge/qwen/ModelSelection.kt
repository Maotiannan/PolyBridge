package dev.polybridge.qwen

import com.google.gson.Gson
import com.google.gson.JsonParser

object ModelIds {
    const val PIPELINE = "pipeline"
    const val LIVE35 = "qwen3.5-livetranslate-flash-realtime"
    const val LIVE38 = "qwen3.8-livetranslate-flash-realtime"
    const val AUDIO31 = "qwen-audio-3.1-realtime-flash"
    const val OMNI38 = "qwen3.8-omni-flash-realtime"
    const val ASR = "qwen3-asr-flash"
    const val ASR31 = "qwen-audio-3.1-asr-flash-streaming"
    const val MT = "qwen-mt-flash"
    const val MT_PLUS = "qwen-mt-plus"
    const val TTS = "qwen3-tts-flash"
    const val TTS_INSTRUCT = "qwen3-tts-instruct-flash"
}

enum class ModelFeature(val label: String, val help: String) {
    FACE("面对面说话", "按住说话的原文、译文和当次译音"),
    LISTEN("自由监听", "听身边的人，自动中法互译"),
    CAPTIONS("屏幕字幕", "听其他应用，只生成文字，不生成译音"),
    REPLY("中文转法语", "说中文、生成法语并可分享"),
    TEXT("文字翻译与重译", "打字、纠错重译及分步方案的翻译阶段"),
    VOICE("朗读与重播", "生成当前译文的声音；已有同模型声音优先复用"),
    ASR("分步识别与异常复核", "仅用于分步方案和异常字幕复核，不改变一体模型内部识别")
}

enum class ModelPreset(val label: String, val live: String, val description: String) {
    CURRENT("当前方案", ModelIds.LIVE35, "保留现用分步翻译与 LiveTranslate 3.5"),
    AUDIO31("Audio 3.1", ModelIds.AUDIO31, "语音功能使用 Audio 3.1；配套文字和朗读保持 Flash"),
    LIVE38("LiveTranslate 3.8", ModelIds.LIVE38, "语音功能使用 3.8 同传；需要业务空间"),
    OMNI38("Omni 3.8 · 实验", ModelIds.OMNI38, "曾出现原样复述而未翻译；需要业务空间")
}

data class ModelEntry(val id: String, val label: String, val note: String = "", val workspaceRequired: Boolean = false)

object ModelCatalog {
    val realtime = listOf(
        ModelEntry(ModelIds.LIVE35, "LiveTranslate 3.5", "当前实时翻译"),
        ModelEntry(ModelIds.AUDIO31, "Audio 3.1", "新接入，仍需比较长语音效果"),
        ModelEntry(ModelIds.LIVE38, "LiveTranslate 3.8", "专用同传，需要业务空间", true),
        ModelEntry(ModelIds.OMNI38, "Omni 3.8 · 实验", "可能原样复述、未翻译，需要业务空间", true))
    private val pipeline = ModelEntry(ModelIds.PIPELINE, "分步识别 → 翻译 → 朗读", "分别使用下方的识别、文字与朗读设置")
    fun choices(feature: ModelFeature): List<ModelEntry> = when (feature) {
        ModelFeature.FACE, ModelFeature.REPLY -> listOf(pipeline) + realtime
        ModelFeature.LISTEN, ModelFeature.CAPTIONS -> realtime
        ModelFeature.TEXT -> listOf(ModelEntry(ModelIds.MT, "MT Flash", "当前文字翻译"), ModelEntry(ModelIds.MT_PLUS, "MT Plus", "不同档位，按账号计费"))
        ModelFeature.VOICE -> listOf(ModelEntry(ModelIds.TTS, "TTS Flash", "当前朗读"), ModelEntry(ModelIds.TTS_INSTRUCT, "TTS Instruct Flash · 待验证", "本次北京账号返回 404，当前不可用；仅供自行核验，不会自动换模型"))
        ModelFeature.ASR -> listOf(ModelEntry(ModelIds.ASR, "ASR Flash", "当前单句识别"), ModelEntry(ModelIds.ASR31, "Audio 3.1 ASR", "流式识别，完整收集所有分段"))
    }
    fun label(id: String) = ModelFeature.entries.flatMap(::choices).firstOrNull { it.id == id }?.label ?: id
    fun validate(feature: ModelFeature, id: String) = require(choices(feature).any { it.id == id }) { "此模型不能用于${feature.label}，请重新选择。" }
}

/** Immutable, versioned user choices. Never silently substitutes an unknown saved model. */
data class ModelSelection(val preset: ModelPreset = ModelPreset.CURRENT, val overrides: Map<ModelFeature, String> = emptyMap()) {
    init { overrides.forEach { (feature, id) -> ModelCatalog.validate(feature, id) } }
    fun model(feature: ModelFeature): String = overrides[feature] ?: when (feature) {
        ModelFeature.FACE, ModelFeature.REPLY -> if (preset == ModelPreset.CURRENT) ModelIds.PIPELINE else preset.live
        ModelFeature.LISTEN, ModelFeature.CAPTIONS -> preset.live
        ModelFeature.TEXT -> ModelIds.MT
        ModelFeature.VOICE -> ModelIds.TTS
        ModelFeature.ASR -> ModelIds.ASR
    }
    val label get() = if (overrides.isEmpty()) preset.label else "自定义 · ${preset.label}"
    fun selectPreset(value: ModelPreset) = ModelSelection(value)
    fun override(feature: ModelFeature, model: String?) = copy(overrides = if (model == null) overrides - feature else overrides + (feature to model))
    fun validate(region: Region, workspace: String) {
        region.endpoint(workspace)
        if (ModelFeature.entries.any { feature -> ModelCatalog.choices(feature).any { it.id == model(feature) && it.workspaceRequired } })
            require(workspace.isNotBlank()) { "所选 3.8 模型需要业务空间 ID，请在账号连接中填写。" }
    }
    fun options(feature: ModelFeature, base: QwenOptions): QwenOptions = base.copy(
        asrModel = model(ModelFeature.ASR), translationModel = model(ModelFeature.TEXT), voiceModel = model(ModelFeature.VOICE),
        liveModel = if (feature in setOf(ModelFeature.LISTEN, ModelFeature.CAPTIONS)) model(feature) else model(ModelFeature.LISTEN),
        utteranceModel = if (feature in setOf(ModelFeature.FACE, ModelFeature.REPLY)) model(feature) else ModelIds.PIPELINE,
        feature = feature.name)
    fun encode(): String = Gson().toJson(mapOf("version" to 1, "preset" to preset.name, "overrides" to overrides.mapKeys { it.key.name }))
    companion object {
        fun decode(text: String?): ModelSelection {
            if (text == null) return ModelSelection()
            try {
                val json = JsonParser.parseString(text).asJsonObject
                require(json["version"].asInt == 1)
                val preset = ModelPreset.valueOf(json["preset"].asString)
                val values = json.getAsJsonObject("overrides").entrySet().associate { ModelFeature.valueOf(it.key) to it.value.asString }
                return ModelSelection(preset, values)
            } catch (_: RuntimeException) { throw IllegalArgumentException("保存的模型方案无法识别，请重新选择整套方案并保存。") }
        }
    }
}
