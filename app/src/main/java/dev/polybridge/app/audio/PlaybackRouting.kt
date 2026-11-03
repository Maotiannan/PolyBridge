package dev.polybridge.app.audio

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.Build
import dev.polybridge.app.TranslationSettings
import kotlin.math.pow

internal fun playbackGain(quietSpeaker: Boolean, deviceType: Int?, index: Int, minIndex: Int,
    volumeDb: (Int) -> Float): Float {
    if (!quietSpeaker) return 1f
    if (deviceType == null) return 0f
    if (deviceType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && deviceType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) return 1f
    if (index <= 0) return 0f
    val lowestAudible = maxOf(1, minIndex)
    if (index <= lowestAudible) return 1f
    val difference = volumeDb(lowestAudible) - volumeDb(index)
    return if (difference.isFinite()) 10.0.pow(difference.coerceAtMost(0f) / 20.0).toFloat() else 0f
}

internal fun headphoneType(type: Int?) = type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET)

internal class PlaybackRouting(context: Context, private val settings: TranslationSettings) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun gain(device: AudioDeviceInfo?): Float {
        val stream = AudioManager.STREAM_MUSIC
        return runCatching {
            val speaker = if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
                else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            playbackGain(settings.state.value.quietSpeaker, device?.type, audio.getStreamVolume(stream),
                audio.getStreamMinVolume(stream)) { audio.getStreamVolumeDb(stream, it, speaker) }
        }.getOrDefault(0f)
    }

    private fun preferred(language: String?, personal: Boolean): AudioDeviceInfo? {
        if (language == null) return null
        val ports = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headphones = ports.filter { headphoneType(it.type) }.sortedBy {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val role = if (personal) HeadsetRole.CHINESE else languageRole(language)
        if (settings.headsets.assignments.value.contains(role))
            return headphones.firstOrNull { settings.headsets.matches(role, it.address) }
        if (personal) return headphones.firstOrNull()
        if (!settings.state.value.splitPlayback) return null
        if (headphones.isEmpty()) return null
        return if (language.startsWith("fr")) ports.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        else headphones.firstOrNull { it.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLE_HEADSET) } ?: headphones.first()
    }

    /** A requested port is only a hint. Gate on the actual device, including every mirrored output. */
    fun observe(port: AudioRouting, language: String?, manual: Boolean, isDucked: () -> Boolean = { false }): (Int) -> TranslationSettings.PlaybackObservation? {
        val playbackId = android.os.SystemClock.elapsedRealtimeNanos()
        val personal = manual && settings.state.value.privateReplay
        val requested = preferred(language, personal)
        requested?.let { runCatching { port.setPreferredDevice(it) } }
        val role = if (personal) HeadsetRole.CHINESE else language?.let(::languageRole)
        return { position ->
            val multiple = if (Build.VERSION.SDK_INT >= 36) port.routedDevices else emptyList()
            val routes = multiple.ifEmpty { listOfNotNull(port.routedDevice) }
            val actual = routes.firstOrNull { !headphoneType(it.type) } ?: routes.firstOrNull()
            actual?.let {
                val assigned = role != null && settings.headsets.assignments.value.contains(role)
                val matched = when {
                    assigned -> routes.all { settings.headsets.matches(requireNotNull(role), it.address) }
                    requested != null -> actual.id == requested.id
                    else -> null
                }
                val requireMatch = assigned || (personal && requested != null)
                val level = if (requireMatch && matched != true) 0f else gain(actual)
                val ducked = isDucked() && level > 0
                TranslationSettings.PlaybackObservation(actual.type, matched, if (ducked) 0f else level, position, manual, playbackId, ducked)
                    .also { settings.noteRoute(language, it) }
            }
        }
    }

    /** Returns the start action for a prepared player. Keep it silent until a real route is reported. */
    fun prepare(media: MediaPlayer, language: String?, manual: Boolean = false, owned: () -> Boolean = { true },
        observed: (TranslationSettings.PlaybackObservation) -> Unit = {}): () -> Unit {
        media.setAudioAttributes(attributes)
        media.setVolume(0f, 0f)
        return {
            val observe = observe(media, language, manual)
            fun adjust(): Boolean {
                val output = observe(media.currentPosition)
                val level = output?.gain ?: 0f
                media.setVolume(level, level)
                output?.let(observed)
                return output != null
            }
            media.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { if (owned()) runCatching { adjust() } }, main)
            media.start()
            val deadline = android.os.SystemClock.elapsedRealtime() + maxOf(1000, media.duration) + 4000L
            // OriginOS may report isPlaying=false while bringing up A2DP. Ownership ends at
            // completion, cancellation or error; a transient startup state must not leave the player muted.
            val check = object : Runnable {
                override fun run() {
                    if (!owned() || android.os.SystemClock.elapsedRealtime() >= deadline) return
                    val known = runCatching { adjust() }.getOrDefault(false)
                    main.postDelayed(this, if (known) 80 else 15)
                }
            }
            check.run()
        }
    }

    private fun languageRole(language: String) = if (language.startsWith("fr")) HeadsetRole.FRENCH else HeadsetRole.CHINESE

    fun prepare(track: AudioTrack): () -> Unit {
        track.setVolume(0f)
        val adjust: () -> Unit = { runCatching { track.setVolume(gain(track.routedDevice)) }; Unit }
        track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { adjust() }, main)
        return adjust
    }
}
