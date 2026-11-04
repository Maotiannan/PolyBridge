package dev.polybridge.audio

import dev.polybridge.core.LiveSpeechSession

interface ListeningFilter : LiveSpeechSession {
    fun recheckOpening(sendOpening: () -> Unit): Boolean
    fun voiceBeforeDuck(samples: ShortArray, length: Int, rate: Int): Boolean = true
    fun resetVoiceBeforeDuck() = Unit
}

/** Preserve the server's sample clock, including silence. A short lookback restores the onset when speech opens. */
class SpeechFilter(model: ByteArray, private val link: LiveSpeechSession, noiseModel: ByteArray? = null) : ListeningFilter, LiveSpeechSession by link {
    private val detector = SpeechDetector(model)
    private val enhancer = try { noiseModel?.let { GtcrnDenoiser(it, ::acceptEnhanced) } }
        catch (failure: Throwable) { detector.close(); throw failure }
    private val evidence = try { noiseModel?.let { SpeechEvidence(model) } }
        catch (failure: Throwable) { try { enhancer?.close() } finally { detector.close() }; throw failure }
    private val frame = ShortArray(SpeechDetector.WINDOW)
    private val resampled = ShortArray(256)
    private var resampledCount = 0
    private val silence = ShortArray(frame.size)
    private val waiting = ArrayDeque<ShortArray>()
    private var used = 0
    private var decimationSum = 0
    private var decimationCount = 0
    private var onset = 0
    private var hangover = 0
    private var openingWindows = 0
    private var confirmations = 0L
    private var finished = false
    private var closed = false

    override fun voiceBeforeDuck(samples: ShortArray, length: Int, rate: Int): Boolean =
        evidence?.accept(samples, length, rate) ?: true
    override fun resetVoiceBeforeDuck() { evidence?.reset() }

    /** Re-evaluate a possible interruption without the preceding echo/silence model state.
     * Energy after muting the output is only a candidate: rain must still pass the voice model.
     * The longer bounded lookback keeps quiet first syllables while that decision is pending. */
    override fun recheckOpening(sendOpening: () -> Unit): Boolean {
        // A rejected probe may belong to the same still-developing utterance. Restarting the
        // recurrent detector on every retry repeatedly forgets it after a noisy prelude.
        if (openingWindows == 0) {
            detector.reset()
            onset = 0; hangover = 0; openingWindows = OPENING_WINDOWS
        }
        val before = confirmations
        sendOpening()
        return confirmations > before
    }

    override fun send(samples: ShortArray, length: Int, rate: Int) {
        require(length in 0..samples.size && (rate == 16000 || rate == 48000))
        check(!finished && !closed)
        val stride = rate / 16000
        for (i in 0 until length) {
            decimationSum += samples[i].toInt()
            if (++decimationCount < stride) continue
            val sample = (decimationSum / stride).toShort()
            decimationSum = 0
            decimationCount = 0
            if (enhancer == null) acceptSample(sample)
            else {
                resampled[resampledCount++] = sample
                if (resampledCount == resampled.size) { enhancer.send(resampled, resampledCount); resampledCount = 0 }
            }
        }
    }

    private fun acceptEnhanced(samples: ShortArray, length: Int) { repeat(length) { acceptSample(samples[it]) } }
    private fun acceptSample(sample: Short) {
        frame[used++] = sample
        if (used == frame.size) classify(used)
    }

    private fun classify(length: Int) {
        if (length < frame.size) frame.fill(0, length)
        val probability = detector.probability(frame)
        onset = if (probability >= OPEN_PROBABILITY) onset + 1 else 0
        if (onset == CONFIRM_WINDOWS) confirmations++
        if (onset >= CONFIRM_WINDOWS || hangover > 0 && probability >= KEEP_PROBABILITY) hangover = TAIL_WINDOWS
        if (hangover > 0) {
            while (waiting.isNotEmpty()) forward(waiting.removeFirst())
            link.send(frame, length, 16000)
            hangover--
            openingWindows = 0
        } else {
            waiting.addLast(frame.copyOf(length))
            val lookback = if (openingWindows > 0) OPENING_WINDOWS else LOOKBACK_WINDOWS
            while (waiting.size > lookback) link.send(silence, waiting.removeFirst().size, 16000)
            // Keep the larger *bounded* lookback until voice is confirmed. Expiring it while
            // a noisy opening is still being classified abruptly trims it to six windows and
            // can discard the first syllables just before the detector finally opens.
        }
        used = 0
    }

    private fun forward(samples: ShortArray) = link.send(samples, samples.size, 16000)

    override fun finish() {
        check(!closed)
        if (finished) return
        finished = true
        if (enhancer != null) {
            if (resampledCount > 0) { enhancer.send(resampled, resampledCount); resampledCount = 0 }
            enhancer.finish()
        }
        if (used > 0) classify(used)
        while (waiting.isNotEmpty()) link.send(silence, waiting.removeFirst().size, 16000)
        link.finish()
    }

    override fun close() {
        if (closed) return
        closed = true
        waiting.clear(); frame.fill(0); resampled.fill(0)
        try { evidence?.close() } finally {
            try { enhancer?.close() } finally { try { detector.close() } finally { link.close() } }
        }
    }

    private companion object {
        const val OPEN_PROBABILITY = .52f
        const val KEEP_PROBABILITY = .32f
        const val CONFIRM_WINDOWS = 2
        const val LOOKBACK_WINDOWS = 6
        const val OPENING_WINDOWS = 20
        const val TAIL_WINDOWS = 8
    }
}
