package dev.polybridge.qwen

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import dev.polybridge.core.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

enum class Region(val label: String, private val regionalHost: String, private val legacyHost: String) {
    BEIJING("北京", "cn-beijing", "dashscope.aliyuncs.com"),
    SINGAPORE("新加坡", "ap-southeast-1", "dashscope-intl.aliyuncs.com");

    fun endpoint(workspace: String): URL {
        require(workspace.isEmpty() || workspace.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9-]{0,62}")))
        val host = if (workspace.isEmpty()) legacyHost else "$workspace.$regionalHost.maas.aliyuncs.com"
        return URL("https://$host/compatible-mode/v1/chat/completions")
    }
}

data class QwenOptions(val region: Region = Region.BEIJING, val workspace: String = "",
    val asrModel: String = "qwen3-asr-flash", val translationModel: String = "qwen-mt-flash",
    val voiceModel: String = "qwen3-tts-flash", val voice: String = "Cherry",
    val liveModel: String = "qwen3.5-livetranslate-flash-realtime",
    val conversationPauseMs: Int = 1200, val mediaPauseMs: Int = 1800,
    val utteranceModel: String = ModelIds.PIPELINE, val feature: String = ModelFeature.FACE.name)

data class ServiceDiagnostic(val httpStatus: Int, val code: String?, val requestId: String?, val hint: String?)

class QwenSpeech internal constructor(private val key: String, private val endpoint: URL,
    private val options: QwenOptions = QwenOptions()) : SpeechTranslation, VoiceSynthesis, UtteranceTranslation {
    constructor(key: String, options: QwenOptions) : this(key, options.region.endpoint(options.workspace), options)
    private val gson = Gson()
    @Volatile var lastTrace: ModelTrace? = null
        private set
    @Volatile var lastFailure: ServiceDiagnostic? = null
        private set

    override fun utterance(side: Side, wave: ByteArray, audio: Boolean, sourceReady: (String) -> Unit): SpeechResult {
        if (options.utteranceModel == ModelIds.PIPELINE) {
            val source = transcribe(side, wave)
            sourceReady(source)
            val target = translate(side, source)
            return SpeechResult(source, target, model = lastTrace)
        }
        return collectUtterance(side, wave, audio, sourceReady) { receiver ->
            QwenLiveSession(key, options.copy(liveModel = options.utteranceModel), !audio, receiver, side, endpoint, held = true)
        }.also { lastTrace = it.model }
    }

    fun live(update: (LiveUpdate) -> Unit): LiveSpeechSession =
        live(false, update)

    fun live(textOnly: Boolean, update: (LiveUpdate) -> Unit): LiveSpeechSession =
        ReconnectingLiveSession({ receiver ->
            if (textOnly) RecoveringCaptionSession({ QwenLiveSession(key, options, true, it, endpoint = endpoint) }, ::recoverCaption, receiver,
                completeText = if (options.feature == ModelFeature.CAPTIONS.name) ::completeCaptionText else null)
            else QwenLiveSession(key, options, false, receiver, endpoint = endpoint)
        }, update)

    private fun completeCaptionText(side: Side, source: String): RecoveredCaption {
        val target = translate(side, source)
        if (CaptionQuality.repetitive(target) || bilingualSide(target) == side)
            throw TranslationFailure(TranslationIssue.UNRELIABLE)
        return RecoveredCaption(side, source, target, lastTrace?.copy(recoveredBy = options.translationModel))
    }

    /** Recheck only anomalous screen captions, without reusing the live recognizer's language. */
    fun recoverCaption(wave: ByteArray): RecoveredCaption {
        if (options.asrModel == ModelIds.ASR31) {
            val recognized = StreamingAsr(key, options, endpoint).recognize(wave, null)
            val side = bilingualSide(recognized.text) ?: throw TranslationFailure(TranslationIssue.UNRELIABLE)
            if (CaptionQuality.repetitive(recognized.text)) throw TranslationFailure(TranslationIssue.UNRELIABLE)
            val target = translate(side, recognized.text)
            if (CaptionQuality.repetitive(target)) throw TranslationFailure(TranslationIssue.UNRELIABLE)
            return RecoveredCaption(side, recognized.text, target, lastTrace?.copy(recoveredBy = options.asrModel, recognition = recognized.trace))
        }
        val data = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wave)
        val audio = mapOf("type" to "input_audio", "input_audio" to mapOf("data" to data))
        val response = post(endpoint, mapOf("model" to options.asrModel,
            "messages" to listOf(message(listOf(audio))), "asr_options" to mapOf("enable_itn" to false)))
        val result = runCatching { response.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message") }
            .getOrNull() ?: throw TranslationFailure(TranslationIssue.UNRELIABLE)
        val source = runCatching { result["content"].asString.trim() }.getOrDefault("")
        val language = runCatching { result.getAsJsonArray("annotations").firstOrNull {
            it.asJsonObject["type"]?.asString == "audio_info"
        }?.asJsonObject?.get("language")?.asString }.getOrNull()
        val side = when (language) {
            "fr" -> Side.FRENCH
            "zh" -> Side.CHINESE
            else -> throw TranslationFailure(TranslationIssue.UNRELIABLE)
        }
        if (source.isBlank() || CaptionQuality.repetitive(source)) throw TranslationFailure(TranslationIssue.UNRELIABLE)
        if (Thread.currentThread().isInterrupted) throw TranslationFailure(TranslationIssue.INTERRUPTED)
        val recognition = lastTrace
        val target = translate(side, source)
        if (CaptionQuality.repetitive(target)) throw TranslationFailure(TranslationIssue.UNRELIABLE)
        return RecoveredCaption(side, source, target, lastTrace?.copy(recoveredBy = options.asrModel, recognition = recognition))
    }

    override fun transcribe(side: Side, wave: ByteArray): String {
        if (options.asrModel == ModelIds.ASR31) return StreamingAsr(key, options, endpoint).recognize(wave, side).also { lastTrace = it.trace }.text
        val data = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wave)
        val audio = mapOf("type" to "input_audio", "input_audio" to mapOf("data" to data))
        return complete(mapOf("model" to options.asrModel, "messages" to listOf(message(listOf(audio))),
            "asr_options" to mapOf("language" to side.source.substringBefore('-'), "enable_itn" to false)))
    }

    override fun translate(side: Side, source: String): String {
        val chinese = side == Side.CHINESE
        val direction = mapOf("source_lang" to if (chinese) "Chinese" else "French",
            "target_lang" to if (chinese) "French" else "Chinese")
        return complete(mapOf("model" to options.translationModel, "messages" to listOf(message(source)),
            "translation_options" to direction))
    }

    private fun message(content: Any) = mapOf("role" to "user", "content" to content)

    override fun synthesize(language: String, text: String): ByteArray {
        val input = mapOf("text" to text, "voice" to options.voice, "language_type" to if (language.startsWith("fr")) "French" else "Chinese")
        val api = URL(endpoint, "/api/v1/services/aigc/multimodal-generation/generation")
        val response = post(api, mapOf("model" to options.voiceModel, "input" to input),
            readTimeoutMs = (8500 + text.length.coerceAtMost(1600) * 20).coerceAtMost(40000))
        val uri = runCatching { URL(response.getAsJsonObject("output").getAsJsonObject("audio").get("url").asString) }
            .getOrNull() ?: throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
        if (uri.protocol !in setOf("http", "https") || !uri.host.endsWith(".aliyuncs.com") || uri.userInfo != null)
            throw TranslationFailure(TranslationIssue.SERVICE)
        val download = URL("https", uri.host, uri.file).openConnection() as HttpURLConnection
        try {
            download.instanceFollowRedirects = false
            download.connectTimeout = 10_000
            download.readTimeout = 25_000
            if (download.responseCode !in 200..299) throw TranslationFailure(TranslationIssue.SERVICE)
            val limit = 8 * 1024 * 1024
            return download.inputStream.use { inputStream ->
                val buffer = ByteArray(8192)
                val result = java.io.ByteArrayOutputStream()
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > limit) throw TranslationFailure(TranslationIssue.LIMIT)
                    result.write(buffer, 0, count)
                }
                if (result.size() == 0) throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
                try { playableWave(result.toByteArray()) }
                catch (_: IllegalArgumentException) { throw TranslationFailure(TranslationIssue.EMPTY_REPLY) }
            }
        } catch (_: IOException) { throw TranslationFailure(TranslationIssue.NETWORK) }
        finally { download.disconnect() }
    }

    private fun complete(body: Map<String, Any>): String {
        val response = post(endpoint, body)
        return runCatching { response.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            .get("content").asString.trim().takeIf(String::isNotEmpty) }.getOrNull() ?: throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
    }

    private fun post(url: URL, body: Map<String, Any>, readTimeoutMs: Int = 40000): JsonObject {
        if (key.isBlank()) throw TranslationFailure(TranslationIssue.CREDENTIAL)
        val call = url.openConnection() as HttpURLConnection
        try {
            call.requestMethod = "POST"
            call.instanceFollowRedirects = false
            call.connectTimeout = 10_000
            call.readTimeout = readTimeoutMs
            call.doOutput = true
            call.setRequestProperty("Authorization", "Bearer $key")
            call.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = gson.toJson(body).toByteArray(Charsets.UTF_8)
            call.setFixedLengthStreamingMode(payload.size)
            call.outputStream.use { it.write(payload) }
            if (call.responseCode !in 200..299) {
                val problem = runCatching { call.errorStream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(4096); val n = reader.read(buffer)
                    if (n > 0) JsonParser.parseString(String(buffer, 0, n)).asJsonObject else null
                } }.getOrNull()
                val message = problem?.get("message")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                lastFailure = ServiceDiagnostic(call.responseCode, problem?.get("code")?.asString?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) },
                    safeIdentifier(problem?.get("request_id")?.asString ?: call.getHeaderField("x-request-id")), when {
                        message.contains("instruction", true) -> "INSTRUCTIONS"
                        message.contains("voice", true) -> "VOICE"
                        message.contains("model", true) -> "MODEL"
                        message.contains("language", true) -> "LANGUAGE"
                        else -> null
                    })
            }
            when (call.responseCode) {
                in 200..299 -> Unit
                401, 403 -> throw TranslationFailure(TranslationIssue.CREDENTIAL)
                429 -> throw TranslationFailure(TranslationIssue.LIMIT)
                else -> throw TranslationFailure(TranslationIssue.SERVICE)
            }
            return call.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                runCatching { JsonParser.parseReader(reader).asJsonObject }.getOrNull() ?: throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
            }.also { response ->
                lastTrace = ModelTrace(body["model"].toString(),
                    actualModel = response["model"]?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,120}")) },
                    requestId = safeIdentifier(response["request_id"]?.asString ?: call.getHeaderField("x-request-id")), feature = options.feature)
            }
        } catch (_: IOException) {
            throw TranslationFailure(TranslationIssue.NETWORK)
        } finally { call.disconnect() }
    }
}
