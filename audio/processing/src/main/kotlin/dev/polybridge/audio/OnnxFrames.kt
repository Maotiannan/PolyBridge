package dev.polybridge.audio

import ai.onnxruntime.*
import java.nio.*

/** Owns only per-stream resources; the process-wide ORT environment remains shared. */
internal class OnnxFrames(model: ByteArray, shapes: Map<String, LongArray>, sampleRate: Long? = null) : AutoCloseable {
    private val environment = audioOperation { OrtEnvironment.getEnvironment() }
    private val session = audioOperation { OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
        environment.createSession(model, options)
    } }
    private val tensors = linkedMapOf<String, OnnxTensor>()
    private val buffers = linkedMapOf<String, FloatBuffer>()
    private var closed = false
    init {
        try {
            for ((name, shape) in shapes) {
                val count = shape.fold(1L, Long::times).toInt()
                val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                buffers[name] = buffer
                tensors[name] = OnnxTensor.createTensor(environment, buffer, shape)
            }
            if (sampleRate != null) tensors["sr"] = OnnxTensor.createTensor(environment,
                LongBuffer.wrap(longArrayOf(sampleRate)), longArrayOf())
        } catch (failure: Exception) { close(); throw AudioProcessingFailure(failure) }
    }
    operator fun get(name: String): FloatBuffer = buffers.getValue(name)
    fun run(read: (OrtSession.Result) -> Unit) {
        check(!closed)
        audioOperation { session.run(tensors).use(read) }
    }
    fun copy(result: OrtSession.Result, output: String, input: String) {
        val values = (result.get(output).get() as OnnxTensor).floatBuffer
        val buffer = get(input)
        buffer.rewind(); buffer.put(values); buffer.rewind()
    }
    fun reset() { buffers.values.forEach { b -> for (i in 0 until b.capacity()) b.put(i, 0f) } }
    override fun close() {
        if (closed) return
        closed = true
        try { tensors.values.forEach { it.close() } } finally { session.close() }
    }
}
