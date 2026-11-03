package dev.polybridge.app.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import dev.polybridge.app.ProbeRequest
import dev.polybridge.core.ProbeEntry
import dev.polybridge.core.SignalStats
import dev.polybridge.core.Verdict
import dev.polybridge.core.assess

internal const val HEADSET_SETUP_TIMEOUT_MS = 15_000L

internal fun <T> companionOutput(inputAddress: String?, outputs: List<T>, address: (T) -> String?): T? {
    fun known(value: String?) = value?.uppercase()?.takeUnless {
        it.isBlank() || it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00"
    }
    val source = known(inputAddress)
    if (source != null) {
        val matches = outputs.filter { known(address(it)) == source }
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }
    return outputs.singleOrNull()?.takeIf { source == null || known(address(it)) == null }
}

internal class HeadsetRoute(private val audio: AudioManager, private val input: AudioDeviceInfo) : AutoCloseable {
    private var ownsMode = false
    private var ownsSelection = false

    fun connect(request: ProbeRequest): ProbeEntry? {
        fun ended() = request.end.get()?.let { request.empty(assess(SignalStats(), 0, null, it)) }
        ended()?.let { return it }
        if (audio.mode != AudioManager.MODE_NORMAL) return request.empty(Verdict.FAILED, "COMMUNICATION_BUSY")
        val candidates = audio.availableCommunicationDevices.filter { it.type == input.type }
        val sink = companionOutput(input.address, candidates) { it.address }
            ?: return request.empty(Verdict.FAILED, "NO_MATCHING_HEADSET")

        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        ownsMode = true
        ownsSelection = audio.setCommunicationDevice(sink)
        if (!ownsSelection) return request.empty(Verdict.FAILED, "ROUTE_REQUEST_REJECTED")

        val deadline = SystemClock.elapsedRealtime() + HEADSET_SETUP_TIMEOUT_MS
        while (audio.communicationDevice?.id != sink.id) {
            ended()?.let { return it }
            if (audio.availableCommunicationDevices.none { it.id == sink.id }) return request.empty(Verdict.ROUTE_LOST)
            if (SystemClock.elapsedRealtime() >= deadline) return request.empty(Verdict.FAILED, "HEADSET_SETUP_TIMEOUT")
            SystemClock.sleep(50)
        }
        return ended()
    }

    override fun close() {
        try {
            if (ownsSelection) audio.clearCommunicationDevice()
        } finally {
            if (ownsMode) audio.mode = AudioManager.MODE_NORMAL
        }
    }
}
