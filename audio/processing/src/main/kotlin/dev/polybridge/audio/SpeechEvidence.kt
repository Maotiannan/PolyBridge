package dev.polybridge.audio

/** Independent VAD clock for the echo-cleaned microphone, before we mute playback.
 * It never forwards audio or advances the recognition/enhancement stream. */
internal class SpeechEvidence(model: ByteArray) : AutoCloseable {
    private val detector = SpeechDetector(model)
    private val frame = ShortArray(SpeechDetector.WINDOW)
    private var used = 0
    private var sum = 0
    private var count = 0
    private var onset = 0
    private var recent = 0

    fun accept(samples: ShortArray, length: Int, rate: Int): Boolean {
        require(length in 0..samples.size && rate in setOf(16000, 48000))
        for (i in 0 until length) {
            sum += samples[i]
            if (++count < rate / 16000) continue
            frame[used++] = (sum / count).toShort()
            sum = 0; count = 0
            if (used == frame.size) {
                val probability = detector.probability(frame)
                onset = if (probability >= .52f) onset + 1 else 0
                recent = if (onset >= 2) 4 else maxOf(0, recent - 1)
                used = 0
            }
        }
        return recent > 0
    }

    fun reset() { detector.reset(); frame.fill(0); used = 0; sum = 0; count = 0; onset = 0; recent = 0 }
    override fun close() = detector.close()
}
