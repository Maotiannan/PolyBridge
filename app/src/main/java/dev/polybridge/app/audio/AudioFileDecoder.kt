package dev.polybridge.app.audio

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.SystemClock
import dev.polybridge.core.ImportedAudio
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer

internal class AudioFileDecoder(private val context: Context) {
    suspend fun read(uri: Uri): ByteArray {
        require(uri.scheme == "content")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("NO_AUDIO")
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) require(format.getLong(MediaFormat.KEY_DURATION) <= 120_000_000) { "DURATION" }
            extractor.selectTrack(track)
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW) return readPcm(extractor, format)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                return decode(extractor, decoder)
            } finally { decoder.release() }
        } finally { extractor.release() }
    }

    private suspend fun readPcm(extractor: MediaExtractor, format: MediaFormat): ByteArray {
        val audio = ImportedAudio()
        var buffer = ByteBuffer.allocate(32768)
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = extractor.sampleSize
            require(size <= 8L * 1024 * 1024) { "PCM_BLOCK_SIZE" }
            if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size.toInt())
            buffer.clear()
            val bytes = extractor.readSampleData(buffer, 0)
            if (bytes < 0) break
            buffer.position(0); buffer.limit(bytes)
            append(audio, buffer, format)
            if (!extractor.advance()) break
        }
        return audio.wave()
    }

    private fun append(audio: ImportedAudio, buffer: ByteBuffer, format: MediaFormat) {
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "PCM_FORMAT" }
        audio.add(buffer, format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            encoding == AudioFormat.ENCODING_PCM_FLOAT)
    }

    private suspend fun decode(input: MediaExtractor, decoder: MediaCodec): ByteArray {
        val info = MediaCodec.BufferInfo()
        val audio = ImportedAudio()
        var inputDone = false
        var lastProgress = SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(5000)
                if (index >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(index))
                    val size = input.readSampleData(buffer, 0)
                    inputDone = size < 0
                    decoder.queueInputBuffer(index, 0, size.coerceAtLeast(0), input.sampleTime.coerceAtLeast(0),
                        if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (!inputDone) input.advance()
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            val out = decoder.dequeueOutputBuffer(info, 5000)
            if (out >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = requireNotNull(decoder.getOutputBuffer(out))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        append(audio, buffer, decoder.getOutputFormat(out))
                    }
                } finally { decoder.releaseOutputBuffer(out, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return audio.wave()
                lastProgress = SystemClock.elapsedRealtime()
            }
            check(SystemClock.elapsedRealtime() - lastProgress < 8000) { "DECODER_TIMEOUT" }
        }
    }
}
