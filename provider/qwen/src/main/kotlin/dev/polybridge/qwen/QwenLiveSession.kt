package dev.polybridge.qwen

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.polybridge.core.*
import okhttp3.*
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class QwenLiveSession(private val token: String, private val options: QwenOptions,
    private val update: (LiveUpdate) -> Unit) : LiveSpeechSession {
    private val ready = CountDownLatch(2)
    private val ended = CountDownLatch(2)
    private val stopped = AtomicBoolean()
    private val error = AtomicReference<TranslationIssue?>()
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
    private val frame = ByteArray(3200)
    private var used = 0
    private val gson = Gson()

    override fun connect() {
        val gateway = options.region.endpoint(options.workspace).host
        val uri = "wss://$gateway/api-ws/v1/realtime?model=${options.liveModel}"
        val request = Request.Builder().url(uri).addHeader("Authorization", "Bearer $token").build()
        for (side in Side.entries) {
            if (stopped.get()) throw TranslationFailure(TranslationIssue.INTERRUPTED)
            val events = RealtimeEvents(side, update)
            val listener = object : WebSocketListener() {
                var configured = false
                var remoteFinished = false
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (stopped.get()) return
                    try {
                        val json = JsonParser.parseString(text).asJsonObject
                        when (json["type"]?.asString) {
                            "session.created" -> {
                                val config = mapOf("modalities" to listOf("text", "audio"),
                                    "translation" to mapOf("language" to side.target.substringBefore('-')),
                                    "input_audio_transcription" to mapOf("model" to "qwen3-asr-flash-realtime"))
                                webSocket.send(gson.toJson(mapOf("type" to "session.update", "session" to config)))
                            }
                            "session.updated" -> if (!configured) { configured = true; ready.countDown() }
                            "session.finished" -> if (!remoteFinished) { remoteFinished = true; ended.countDown() }
                            "error" -> fail(TranslationIssue.SERVICE)
                            else -> events.accept(text)
                        }
                    } catch (e: TranslationFailure) { fail(e.issue) }
                    catch (_: RuntimeException) { fail(TranslationIssue.SERVICE) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val reason = when (response?.code) {
                        401, 403 -> TranslationIssue.CREDENTIAL
                        429 -> TranslationIssue.LIMIT
                        else -> TranslationIssue.NETWORK
                    }
                    fail(reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!stopped.get() && !remoteFinished) fail(TranslationIssue.NETWORK)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }
            val socket = client.newWebSocket(request, listener)
            sockets += socket
            if (stopped.get()) socket.cancel()
        }
        if (!ready.await(12, TimeUnit.SECONDS)) fail(TranslationIssue.NETWORK)
        error.get()?.let { throw TranslationFailure(it) }
        if (stopped.get()) throw TranslationFailure(TranslationIssue.INTERRUPTED)
    }

    override fun send(samples: ShortArray, length: Int, rate: Int) {
        require(length in 0..samples.size && (rate == 16000 || rate == 48000))
        if (stopped.get()) throw TranslationFailure(error.get() ?: TranslationIssue.INTERRUPTED)
        val stride = rate / 16000
        var i = 0
        while (i + stride <= length) {
            var sum = 0
            repeat(stride) { sum += samples[i++].toInt() }
            val sample = sum / stride
            frame[used++] = sample.toByte()
            frame[used++] = (sample shr 8).toByte()
            if (used == frame.size) flush()
        }
    }

    private fun flush() {
        if (used == 0) return
        val data = Base64.getEncoder().encodeToString(if (used == frame.size) frame else frame.copyOf(used))
        val packet = """{"type":"input_audio_buffer.append","audio":"$data"}"""
        used = 0
        if (sockets.any { it.queueSize() > 256 * 1024 || !it.send(packet) }) throw TranslationFailure(TranslationIssue.NETWORK)
    }

    override fun finish() {
        if (stopped.get()) return
        flush()
        sockets.forEach { it.send("""{"type":"session.finish"}""") }
        ended.await(5, TimeUnit.SECONDS)
    }

    private fun fail(reason: TranslationIssue) {
        if (stopped.get() || !error.compareAndSet(null, reason)) return
        close()
        update(LiveUpdate.Stopped(reason))
    }

    override fun close() {
        stopped.set(true)
        repeat(2) { ready.countDown(); ended.countDown() }
        sockets.forEach { it.cancel() }
    }

    private companion object {
        val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
}
