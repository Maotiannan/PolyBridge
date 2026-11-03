package dev.polybridge.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import dev.polybridge.core.VoiceSynthesis
import kotlinx.coroutines.*
import java.util.Locale

/** Only one voice owns the speaker. A new press or a screen change invalidates pending speech. */
internal class ConversationVoice(private val context: Context, private val provider: () -> VoiceSynthesis?,
    private val notice: (String) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var load: Job? = null
    private var player: MediaPlayer? = null
    private var ready = false
    private val system = TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS }

    fun stop() {
        load?.cancel()
        player?.release()
        player = null
        system.stop()
    }

    fun phrase(asset: String) {
        stop()
        try {
            prepare { media -> context.assets.openFd("phrasebook/$asset.wav").use {
                media.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            } }
        } catch (_: Exception) { notice("词本声音未能播放，请重试") }
    }

    fun translation(language: String, text: String) {
        stop()
        load = scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { provider()?.synthesize(language, text) }.getOrNull() }
            ensureActive()
            if (bytes != null) {
                try { prepare { it.setDataSource(VoiceData(bytes)) }; return@launch }
                catch (_: RuntimeException) { }
                catch (_: java.io.IOException) { }
            }
            if (!ready || system.setLanguage(Locale.forLanguageTag(language)) < 0 ||
                system.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation") == TextToSpeech.ERROR)
                notice("译文已保存，当前无法朗读。请检查网络或手机语音设置。")
        }
    }

    private fun prepare(source: (MediaPlayer) -> Unit) {
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            source(media)
            media.setOnPreparedListener { if (player === it) it.start() }
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
