package dev.polybridge.audio

import ai.onnxruntime.OnnxTensor

/** Stateful Silero, never shared between listening sessions or the two evidence paths. */
internal class SpeechDetector(model: ByteArray) : AutoCloseable {
    private val engine = OnnxFrames(model, linkedMapOf("input" to longArrayOf(1, 576),
        "state" to longArrayOf(2, 1, 128)), sampleRate = 16000)
    fun probability(samples: ShortArray): Float {
        require(samples.size == WINDOW)
        val input = engine["input"]
        for (i in samples.indices) input.put(64 + i, samples[i] / 32768f)
        var probability = 0f
        engine.run { result ->
            val next = (result[1] as OnnxTensor).floatBuffer
            val state = engine["state"]
            state.rewind(); state.put(next); state.rewind()
            for (i in 0 until 64) input.put(i, input.get(WINDOW + i))
            probability = (result[0] as OnnxTensor).floatBuffer.get(0)
        }
        return probability
    }
    fun reset() = engine.reset()
    override fun close() = engine.close()
    companion object { const val WINDOW = 512 }
}
