package dev.polybridge.app.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.polybridge.core.LiveSpeechSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Run once per listening session on the audio worker. Never share a model's recurrent state between sessions. */
private class SpeechDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        context.assets.open("speech-detection/silero-16k.onnx").use { env.createSession(it.readBytes(), options) }
    }
    private fun floats(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val input = floats(CONTEXT + WINDOW)
    private val state = floats(256)
    private val tensors = mapOf(
        "input" to OnnxTensor.createTensor(env, input, longArrayOf(1, (CONTEXT + WINDOW).toLong())),
        "state" to OnnxTensor.createTensor(env, state, longArrayOf(2, 1, 128)),
        "sr" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf())
    )

    fun probability(samples: ShortArray): Float {
        for (i in samples.indices) input.put(CONTEXT + i, samples[i] / 32768f)
        session.run(tensors).use { output ->
            state.rewind()
            state.put((output[1] as OnnxTensor).floatBuffer)
            state.rewind()
            for (i in 0 until CONTEXT) input.put(i, input.get(WINDOW + i))
            return (output[0] as OnnxTensor).floatBuffer.get(0)
        }
    }

    fun reset() {
        for (i in 0 until input.capacity()) input.put(i, 0f)
        for (i in 0 until state.capacity()) state.put(i, 0f)
    }

    override fun close() {
        tensors.values.forEach { it.close() }
        session.close()
    }

    companion object {
        const val WINDOW = 512
        private const val CONTEXT = 64
    }
}

/** Preserve the server's sample clock, including silence. A short lookback restores the onset when speech opens. */
internal class SpeechGate(context: Context, private val link: LiveSpeechSession) : LiveSpeechSession by link {
    private val detector = SpeechDetector(context)
    private val frame = ShortArray(SpeechDetector.WINDOW)
    private val silence = ShortArray(frame.size)
    private val waiting = ArrayDeque<ShortArray>()
    private var used = 0
    private var decimationSum = 0
    private var decimationCount = 0
    private var onset = 0
    private var hangover = 0
    private var openingWindows = 0
    private var confirmations = 0L

    /** Re-evaluate a possible interruption without the preceding echo/silence model state.
     * Energy after muting the output is only a candidate: rain must still pass the voice model.
     * The longer bounded lookback keeps quiet first syllables while that decision is pending. */
    fun recheckOpening(sendOpening: () -> Unit): Boolean {
        detector.reset()
        onset = 0; hangover = 0; openingWindows = OPENING_WINDOWS
        val before = confirmations
        sendOpening()
        return confirmations > before
    }

    override fun send(samples: ShortArray, length: Int, rate: Int) {
        require(rate == 16000 || rate == 48000)
        val stride = rate / 16000
        for (i in 0 until length) {
            decimationSum += samples[i].toInt()
            if (++decimationCount < stride) continue
            frame[used++] = (decimationSum / stride).toShort()
            decimationSum = 0
            decimationCount = 0
            if (used == frame.size) classify(used)
        }
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
            if (openingWindows > 0) openingWindows--
        }
        used = 0
    }

    private fun forward(samples: ShortArray) = link.send(samples, samples.size, 16000)

    override fun finish() {
        if (used > 0) classify(used)
        while (waiting.isNotEmpty()) link.send(silence, waiting.removeFirst().size, 16000)
        link.finish()
    }

    override fun close() {
        try { detector.close() } finally { link.close() }
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
