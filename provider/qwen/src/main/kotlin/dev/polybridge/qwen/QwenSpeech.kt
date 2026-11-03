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
    val liveModel: String = "qwen3.5-livetranslate-flash-realtime")

class QwenSpeech internal constructor(private val key: String, private val endpoint: URL,
    private val options: QwenOptions = QwenOptions()) : SpeechTranslation, VoiceSynthesis {
    constructor(key: String, options: QwenOptions) : this(key, options.region.endpoint(options.workspace), options)
    private val gson = Gson()

    fun live(update: (LiveUpdate) -> Unit): LiveSpeechSession =
        live(false, update)

    fun live(textOnly: Boolean, update: (LiveUpdate) -> Unit): LiveSpeechSession =
        ReconnectingLiveSession({ receiver -> QwenLiveSession(key, options, textOnly, receiver) }, update)

    override fun transcribe(side: Side, wave: ByteArray): String {
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
            when (call.responseCode) {
                in 200..299 -> Unit
                401, 403 -> throw TranslationFailure(TranslationIssue.CREDENTIAL)
                429 -> throw TranslationFailure(TranslationIssue.LIMIT)
                else -> throw TranslationFailure(TranslationIssue.SERVICE)
            }
            return call.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                runCatching { JsonParser.parseReader(reader).asJsonObject }.getOrNull() ?: throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
            }
        } catch (_: IOException) {
            throw TranslationFailure(TranslationIssue.NETWORK)
        } finally { call.disconnect() }
    }
}
