package dev.polybridge.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.SystemClock
import dev.polybridge.app.ProbeRequest
import dev.polybridge.core.*
import kotlin.math.PI
import kotlin.math.sin

interface AudioInputSource {
    fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry
}
interface AudioOutputSink {
    fun play(preferredId: Int?, stopped: () -> Boolean): ToneResult
}

class AndroidAudioInput(private val context: Context) : AudioInputSource {
    override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
        fun ended(): ProbeEntry? = request.end.get()?.let {
            request.empty(assess(SignalStats(), 0, null, it))
        }
        ended()?.let { return it }
        val preferred = request.inputId?.let { id -> availablePorts(context).find { it.isSource && it.id == id }
            ?: return request.empty(Verdict.ROUTE_LOST) }
        val headset = preferred?.takeIf {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }?.let { HeadsetRoute(context.getSystemService(AudioManager::class.java), it) }
        return try {
            val config = if (request.kind == Kind.PLAYBACK) {
                if (projection == null) return request.empty(Verdict.DENIED, "NO_PROJECTION")
                val pkg = request.target.packageName ?: context.packageName
                val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
                AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUid(uid)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            } else null
            headset.use { route ->
                route?.connect(request)?.let { return it }
                val source = if (route == null) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_COMMUNICATION
                val record = open(config, source) ?: return request.empty(Verdict.FAILED, "RECORDER_INIT")
                try {
                    ended()?.let { return it }
                    if (preferred != null && route == null && !record.setPreferredDevice(preferred))
                        return request.empty(Verdict.FAILED, "ROUTE_REQUEST_REJECTED")
                    capture(record, request, onStarted)
                } finally {
                    runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
                    record.release()
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            request.empty(Verdict.FAILED, "TARGET_NOT_INSTALLED")
        } catch (_: SecurityException) {
            request.empty(Verdict.DENIED, "AUDIO_PERMISSION")
        } catch (error: RuntimeException) {
            request.empty(Verdict.FAILED, "AUDIO_FRAMEWORK_" + error.javaClass.simpleName)
        }
    }

    private fun open(playback: AudioPlaybackCaptureConfiguration?, source: Int): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone permission is required")
        for (rate in listOf(16000, 48000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setSampleRate(rate).build()
            val builder = AudioRecord.Builder().setContext(context).setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min * 2, rate / 5 * 2))
            if (playback == null) builder.setAudioSource(source)
            else builder.setAudioPlaybackCaptureConfig(playback)
            val record = try { builder.build() } catch (_: UnsupportedOperationException) { continue }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    private fun capture(record: AudioRecord, request: ProbeRequest, onStarted: () -> Unit): ProbeEntry {
        val hold = request.kind == Kind.HOLD
        val duration = if (hold) 30_000L else 10_000L
        val meter = SignalMeter()
        val buffer = ShortArray(record.sampleRate / 50)
        var route: Route? = null
        var systemSilenced: Boolean? = null
        var nextObservation = 0L
        var reason: String? = null
        var end = if (hold) End.CANCELLED else End.TIMED
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return request.empty(Verdict.FAILED, "START_FAILED")
        val start = SystemClock.elapsedRealtime()
        onStarted()
        while (SystemClock.elapsedRealtime() - start < duration) {
            val requestedEnd = request.end.get()
            if (requestedEnd != null) { end = requestedEnd; break }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextObservation) {
                nextObservation = now + 100
                optionalAudioInfo { record.routedDevice?.route() }?.let { route = it }
                optionalAudioInfo { record.activeRecordingConfiguration?.isClientSilenced }?.let {
                    systemSilenced = systemSilenced == true || it
                }
                if (request.inputId != null && availablePorts(context).none { it.id == request.inputId && it.isSource }) {
                    end = End.ROUTE_LOST; break
                }
            }
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            if (read < 0) { end = End.READ_ERROR; reason = "READ_$read"; break }
            if (read == 0) SystemClock.sleep(8) else meter.add(buffer, read)
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        val stats = meter.snapshot()
        val expected = if (hold) record.sampleRate / 10L else record.sampleRate * duration / 1000
        val signal = assess(stats, expected, systemSilenced, request.end.get() ?: end)
        val verdict = qualifyRoute(signal, request.inputId, route)
        return request.empty(verdict, reason).copy(inputRoute = route, sampleRateHz = record.sampleRate,
            channels = record.channelCount, elapsedMs = elapsed, signal = stats, systemSilenced = systemSilenced)
    }
}

// Some audio servers do not expose the capture configuration even while PCM is readable.
private inline fun <T> optionalAudioInfo(read: () -> T?): T? = try {
    read()
} catch (_: RuntimeException) {
    null
}

class AndroidTestTone(private val context: Context) : AudioOutputSink {
    override fun play(preferredId: Int?, stopped: () -> Boolean): ToneResult {
        val rate = 48000
        val samples = ShortArray(rate * 3 / 4) { (2600 * sin(2 * PI * 660 * it / rate)).toInt().toShort() }
        var route: Route? = null
        var played = false
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        try {
            val preferred = preferredId?.let { id -> availablePorts(context).find { it.id == id && it.isSink } }
            val usable = preferredId == null || (preferred != null && track.setPreferredDevice(preferred))
            if (usable && !stopped() && track.write(samples, 0, samples.size) == samples.size) {
                track.play()
                val start = SystemClock.elapsedRealtime()
                while (!stopped() && SystemClock.elapsedRealtime() - start < 1500) {
                    optionalAudioInfo { track.routedDevice?.route() }?.let { route = it }
                    val frames = track.playbackHeadPosition
                    if (frames > 0) played = true
                    if (frames >= samples.size) break
                    SystemClock.sleep(20)
                }
            }
        } finally { track.release() }
        return ToneResult(route, preferredId, played, System.currentTimeMillis())
    }
}
