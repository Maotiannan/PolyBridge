package dev.polybridge.core

/** Mono or interleaved PCM from a future explicitly enabled streaming session. */
class PcmFrame(val samples: ShortArray, val sampleRateHz: Int, val channels: Int, val timestampNs: Long)

interface AudioFeed : AutoCloseable {
    fun write(frame: PcmFrame)
}

interface SpeechToSpeechProvider {
    val capability: Capability
    fun open(side: Side, onText: (String) -> Unit, onAudio: (PcmFrame) -> Unit): AudioFeed
}

/** A future separately installed bridge must negotiate and restore each direction independently. */
interface PrivilegedAudioBridge {
    val downlink: Capability
    val micInjection: Capability
    fun capture(target: PlaybackTarget, onAudio: (PcmFrame) -> Unit): AutoCloseable
    fun inject(target: PlaybackTarget): AudioFeed
    fun restoreOriginalMic()
}
