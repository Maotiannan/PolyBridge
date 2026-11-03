package dev.polybridge.app.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.polybridge.app.TranslationSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Most cloud and live voices are PCM. Keep the container intact for sharing and decode its header once for playback. */
internal data class VoicePcm(val bytes: ByteArray, val offset: Int, val length: Int, val rate: Int, val channels: Int = 1) {
    companion object {
        fun wave(bytes: ByteArray): VoicePcm? {
            if (bytes.size < 44) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(pos: Int) = String(bytes, pos, 4, Charsets.US_ASCII)
            if (tag(0) != "RIFF" || tag(8) != "WAVE") return null
            var rate = 0
            var channels = 0
            var p = 12
            while (p <= bytes.size - 8) {
                val length = b.getInt(p + 4)
                val start = p + 8
                if (length !in 0..(bytes.size - start)) return null
                when (tag(p)) {
                    "fmt " -> {
                        if (length < 16 || b.getShort(start).toInt() != 1 || b.getShort(start + 14).toInt() != 16) return null
                        channels = b.getShort(start + 2).toInt()
                        rate = b.getInt(start + 4)
                    }
                    "data" -> return if (rate in 8000..96000 && channels in 1..2 && length > 0 && length % (2 * channels) == 0)
                        VoicePcm(bytes, start, length, rate, channels) else null
                }
                p = start + length + (length and 1)
            }
            return null
        }
    }
}

/** Establish the route with silence. Speech only enters the stream after its hardware playback head advances. */
internal class PcmSpeechPlayer(private val routing: PlaybackRouting) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var active: AudioTrack? = null
    private var pump: Runnable? = null
    private var ducked = false

    fun duck(enabled: Boolean) {
        ducked = enabled
        if (enabled) active?.setVolume(0f)
    }

    fun play(pcm: VoicePcm, language: String, manual: Boolean, observe: (TranslationSettings.PlaybackObservation) -> Unit,
        done: (String?) -> Unit) {
        close()
        val frameSize = pcm.channels * 2
        val mask = if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val buffer = maxOf(AudioTrack.getMinBufferSize(pcm.rate, mask, AudioFormat.ENCODING_PCM_16BIT), pcm.rate / 5 * frameSize)
        val track = AudioTrack.Builder().setAudioAttributes(routing.attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.rate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build()
        active = track
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            track.setVolume(0f)
            val progress = routing.observe(track, language, manual) { ducked }
            val silence = ByteArray(buffer)
            var primedBytes = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
            check(primedBytes >= 0)
            var written = 0
            var padded = 0
            val tail = pcm.rate / 10 * frameSize
            var feeding = false
            var lastHead = 0
            var moved = SystemClock.elapsedRealtime()
            val began = moved
            track.play()
            fun finish(error: String?) { close(); done(error) }
            val tick = object : Runnable {
                override fun run() {
                    if (active !== track) return
                    try {
                        val now = SystemClock.elapsedRealtime()
                        val head = track.playbackHeadPosition
                        if (head != lastHead) { lastHead = head; moved = now }
                        val heard = (head - primedBytes / frameSize).coerceIn(0, pcm.length / frameSize)
                        val output = progress((heard.toLong() * 1000 / pcm.rate).toInt())
                        track.setVolume(output?.gain ?: 0f)
                        output?.let(observe)
                        if (written == pcm.length && head >= (primedBytes + pcm.length + tail) / frameSize) { finish(null); return }
                        if (!feeding && output != null && output.gain > 0 && head > 0) feeding = true
                        if ((!feeding && now - began >= 3500) || now - moved >= 3500) {
                            finish(if (output?.matchedPreference == false) "指定耳机尚未就绪，请重新连接后重播" else "音频输出未启动，请检查耳机和音量"); return
                        }
                        if (feeding && output != null && (output.gain > 0 || output.ducked) && written < pcm.length) {
                            val n = track.write(pcm.bytes, pcm.offset + written, minOf(buffer, pcm.length - written), AudioTrack.WRITE_NON_BLOCKING)
                            check(n >= 0); written += n
                        } else if (feeding && written == pcm.length && padded < tail) {
                            val n = track.write(silence, 0, minOf(silence.size, tail - padded), AudioTrack.WRITE_NON_BLOCKING)
                            check(n >= 0); padded += n
                        } else if (!feeding) {
                            val n = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                            check(n >= 0); primedBytes += n
                        }
                        main.postDelayed(this, 15)
                    } catch (_: RuntimeException) { finish("音频输出中断，请重播") }
                }
            }
            pump = tick
            tick.run()
        } catch (error: RuntimeException) { close(); throw error }
    }

    override fun close() {
        pump?.let(main::removeCallbacks); pump = null
        active?.let { track ->
            runCatching { track.pause(); track.flush() }
            track.release()
        }
        active = null
        ducked = false
    }
}
