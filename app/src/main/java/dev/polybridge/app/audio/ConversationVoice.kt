package dev.polybridge.app.audio

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.polybridge.app.TranslationSettings
import dev.polybridge.core.Turn
import dev.polybridge.core.TranslationFailure
import dev.polybridge.core.TranslationIssue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.UUID
import dev.polybridge.qwen.ModelCatalog

/** Only one voice owns the speaker. A new press or a screen change invalidates pending speech. */
internal class ConversationVoice(private val context: Context, private val settings: TranslationSettings,
    private val clips: VoiceClips = VoiceClips(),
    private val notice: (String) -> Unit) : AutoCloseable {
    private val routing = PlaybackRouting(context, settings)
    private val pcmPlayer = PcmSpeechPlayer(routing)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var load: Job? = null
    private var player: MediaPlayer? = null
    private var ready = false
    private val system = TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS }
    data class Feedback(val id: String, val message: String, val manual: Boolean, val active: Boolean = true, val model: String? = null)
    private val feedback = MutableStateFlow<Feedback?>(null)
    val state = feedback.asStateFlow()
    private data class LocalSpeech(val id: String, val language: String, val manual: Boolean, val audio: ParcelFileDescriptor)
    private var local: LocalSpeech? = null

    init {
        system.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = localFinished(utteranceId, true)
            @Deprecated("TTS may still call the legacy error callback")
            override fun onError(utteranceId: String?) = localFinished(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = localFinished(utteranceId, false)
        })
    }

    private fun localFinished(id: String?, success: Boolean) {
        scope.launch {
            val utterance = local?.takeIf { it.id == id } ?: return@launch
            local = null
            utterance.audio.use { fd ->
                if (!success || runCatching { prepare(utterance.language, utterance.manual) { it.setDataSource(fd.fileDescriptor) } }.isFailure)
                    failed("手机语音未能朗读，请检查网络或语音设置")
            }
        }
    }

    fun stop() {
        load?.cancel()
        player?.release()
        player = null
        pcmPlayer.close()
        system.stop()
        local?.audio?.close()
        local = null
        feedback.value = null
    }

    fun phrase(asset: String) {
        stop()
        try {
            prepare(if (asset.endsWith("-fr")) "fr-FR" else "zh-CN") { media -> context.assets.openFd("phrasebook/$asset.wav").use {
                media.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            } }
        } catch (_: Exception) { notice("词本声音未能播放，请重试") }
    }

    private fun failed(message: String) {
        feedback.value = feedback.value?.copy(message = message, active = false)
        notice(message)
    }

    fun translation(turn: Turn, manual: Boolean) {
        stop()
        val language = turn.side.target
        val text = turn.translatedText
        val options = runCatching { settings.state.value.optionsFor(turn) }.getOrElse {
            feedback.value = Feedback(turn.id, it.message ?: "请重新选择模型", manual, false); return
        }
        val key = clips.key(turn, options)
        feedback.value = Feedback(turn.id, "准备声音…", manual)
        load = scope.launch {
            val cached = clips.find(turn, options)
            feedback.value = feedback.value?.copy(model = cached?.model ?: options.voiceModel)
            suspend fun synthesize() = withContext(Dispatchers.IO) { runCatching { settings.provider(options)?.synthesize(language, text) } }
            val bytes = cached?.wave ?: run {
                feedback.value = feedback.value?.copy(message = "${ModelCatalog.label(options.voiceModel)} 正在生成声音…")
                val first = synthesize()
                if ((first.exceptionOrNull() as? TranslationFailure)?.issue == TranslationIssue.NETWORK) {
                    feedback.value = feedback.value?.copy(message = "连接超时，正在重试…")
                    synthesize().getOrNull()
                } else first.getOrNull()
            }
            ensureActive()
            if (bytes != null) {
                try {
                    val pcm = VoicePcm.wave(bytes)
                    if (pcm == null) prepare(language, manual) { it.setDataSource(VoiceData(bytes)) }
                    else pcmPlayer.play(pcm, language, manual, ::displayOutput) { error ->
                        if (error == null) feedback.value = null else failed(error)
                    }
                    if (cached == null) clips.put(key, bytes)
                    return@launch
                }
                catch (_: RuntimeException) { }
                catch (_: java.io.IOException) { }
            }
            failed("所选千问朗读失败，未自动更换模型。可重试或选手机语音。")
        }
    }

    fun phoneTranslation(turn: Turn) {
        stop()
        feedback.value = Feedback(turn.id, "手机语音正在生成声音…", true, model = "手机语音")
        if (!localSpeech(turn.side.target, turn.translatedText, true)) failed("手机语音暂不可用，请检查系统语音设置")
    }

    // The TTS engine writes to an unlinked descriptor. Playback always uses the same route and volume policy.
    private fun localSpeech(language: String, text: String, manual: Boolean): Boolean {
        if (!ready || system.setLanguage(Locale.forLanguageTag(language)) < 0) return false
        return runCatching {
            val file = File.createTempFile("synthesis-", ".wav", context.cacheDir)
            val fd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE) } finally { file.delete() }
            val utterance = LocalSpeech(UUID.randomUUID().toString(), language, manual, fd)
            local = utterance
            check(system.synthesizeToFile(text, android.os.Bundle(), fd, utterance.id) == TextToSpeech.SUCCESS)
            true
        }.getOrElse { local?.audio?.close(); local = null; false }
    }

    private fun displayOutput(output: TranslationSettings.PlaybackObservation) {
        feedback.value = feedback.value?.copy(message = when {
            output.gain <= 0 && output.matchedPreference == false -> "等待指定耳机"
            output.gain <= 0 -> "当前输出已静音"
            output.positionMs == 0 -> "准备声音…"
            headphoneType(output.type) -> "耳机播放中"
            else -> "正在${typeLabel(output.type)}播放"
        })
    }

    private fun prepare(language: String, manual: Boolean = false, source: (MediaPlayer) -> Unit) {
        val media = MediaPlayer()
        try {
            var audibleRoute = false
            val start = routing.prepare(media, language, manual, owned = { player === media }) { output ->
                if (player === media) {
                    audibleRoute = audibleRoute || output.gain > 0f
                    displayOutput(output)
                }
            }
            source(media)
            media.setOnPreparedListener { if (player === it) runCatching(start).onFailure { stop(); failed("启动播放失败，请重试") } }
            media.setOnCompletionListener { if (player === it) {
                player = null; it.release()
                if (audibleRoute) feedback.value = null else failed("没有声音到达可用输出，请检查耳机连接和音量")
            } }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) { player = null; failed.release(); this.failed("声音播放失败，请重试") }; true
            }
            player = media
            media.prepareAsync()
        } catch (failure: Exception) {
            player = null
            media.release()
            throw failure
        }
    }

    override fun close() { stop(); scope.cancel(); system.shutdown() }
}

internal class VoiceData(private val data: ByteArray) : MediaDataSource() {
    override fun getSize() = data.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0 || position >= data.size) return -1
        val n = minOf(size, data.size - position.toInt())
        data.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
        return n
    }
    override fun close() = Unit
}
