package dev.polybridge.app.audio

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.polybridge.app.TranslationSettings
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.UUID

/** Only one voice owns the speaker. A new press or a screen change invalidates pending speech. */
internal class ConversationVoice(private val context: Context, private val settings: TranslationSettings,
    private val notice: (String) -> Unit) : AutoCloseable {
    private val routing = PlaybackRouting(context, settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var load: Job? = null
    private var player: MediaPlayer? = null
    private var ready = false
    private val system = TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS }
    private data class LocalSpeech(val id: String, val language: String, val audio: ParcelFileDescriptor)
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
                if (!success || runCatching { prepare(utterance.language) { it.setDataSource(fd.fileDescriptor) } }.isFailure)
                    notice("译文已保存，手机语音未能朗读。")
            }
        }
    }

    fun stop() {
        load?.cancel()
        player?.release()
        player = null
        system.stop()
        local?.audio?.close()
        local = null
    }

    fun phrase(asset: String) {
        stop()
        try {
            prepare(if (asset.endsWith("-fr")) "fr-FR" else "zh-CN") { media -> context.assets.openFd("phrasebook/$asset.wav").use {
                media.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            } }
        } catch (_: Exception) { notice("词本声音未能播放，请重试") }
    }

    fun translation(language: String, text: String) {
        stop()
        load = scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { settings.provider()?.synthesize(language, text) }.getOrNull() }
            ensureActive()
            if (bytes != null) {
                try { prepare(language) { it.setDataSource(VoiceData(bytes)) }; return@launch }
                catch (_: RuntimeException) { }
                catch (_: java.io.IOException) { }
            }
            if (!localSpeech(language, text))
                notice("译文已保存，当前无法朗读。请检查网络或手机语音设置。")
        }
    }

    // The TTS engine writes to an unlinked descriptor. Playback always uses the same route and volume policy.
    private fun localSpeech(language: String, text: String): Boolean {
        if (!ready || system.setLanguage(Locale.forLanguageTag(language)) < 0) return false
        return runCatching {
            val file = File.createTempFile("synthesis-", ".wav", context.cacheDir)
            val fd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE) } finally { file.delete() }
            val utterance = LocalSpeech(UUID.randomUUID().toString(), language, fd)
            local = utterance
            check(system.synthesizeToFile(text, android.os.Bundle(), fd, utterance.id) == TextToSpeech.SUCCESS)
            true
        }.getOrElse { local?.audio?.close(); local = null; false }
    }

    private fun prepare(language: String, source: (MediaPlayer) -> Unit) {
        val media = MediaPlayer()
        try {
            val start = routing.prepare(media, language)
            source(media)
            media.setOnPreparedListener { if (player === it) start() }
            media.setOnCompletionListener { if (player === it) { player = null; it.release() } }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) { player = null; failed.release(); notice("声音播放失败") }; true
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
