package dev.polybridge.qwen

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.polybridge.core.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class RecognizedSpeech(val text: String, val trace: ModelTrace)

/** Bounded single-recording adapter; final sentence snapshots replace partial snapshots. */
internal class StreamingAsr(private val key: String, private val options: QwenOptions, private val endpoint: URL) {
    fun recognize(wave: ByteArray, side: Side?): RecognizedSpeech {
        val input = readWave(wave)
        val task = UUID.randomUUID().toString().replace("-", "")
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closed = AtomicBoolean()
        val error = AtomicReference<TranslationIssue?>()
        val lines = sortedMapOf<Int, Pair<String, Boolean>>()
        val lock = Any()
        var trace = ModelTrace(options.asrModel, sessionId = task, feature = options.feature)
        fun fail(issue: TranslationIssue) { error.compareAndSet(null, issue); ready.countDown(); finished.countDown() }
        fun header(action: String) = mapOf("action" to action, "task_id" to task, "streaming" to "duplex")
        val gson = Gson()
        val uri = (if (endpoint.protocol == "https") "wss" else "ws") + "://${endpoint.authority}/api-ws/v1/inference/"
        val socket = client.newWebSocket(Request.Builder().url(uri).header("Authorization", "Bearer $key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trace = trace.copy(requestId = safeIdentifier(response.header("x-request-id")))
                val parameters = mapOf("format" to "pcm", "sample_rate" to 16000, "heartbeat" to true,
                    "language_hints" to (side?.let { listOf(it.source.substringBefore('-')) } ?: listOf("zh", "fr")), "max_sentence_silence" to 800)
                val payload = mapOf("task_group" to "audio", "task" to "asr", "function" to "recognition", "model" to options.asrModel,
                    "parameters" to parameters, "input" to emptyMap<String, String>())
                if (!webSocket.send(gson.toJson(mapOf("header" to header("run-task"), "payload" to payload)))) fail(TranslationIssue.NETWORK)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                try {
                    require(text.length <= 256000)
                    val event = JsonParser.parseString(text).asJsonObject
                    val head = event.getAsJsonObject("header")
                    when (head["event"]?.asString) {
                        "task-started" -> ready.countDown()
                        "task-finished" -> finished.countDown()
                        "task-failed" -> fail(serviceIssue(head["error_code"]?.asString.orEmpty()))
                        "result-generated" -> {
                            val output = event.getAsJsonObject("payload")?.getAsJsonObject("output") ?: return
                            trace = trace.copy(requestId = safeIdentifier(output["request_id"]?.asString) ?: trace.requestId)
                            val sentence = output.getAsJsonObject("sentence") ?: return
                            if (sentence["heartbeat"]?.asBoolean == true) return
                            val id = sentence["sentence_id"]?.asInt ?: sentence["begin_time"]?.asInt ?: throw IllegalArgumentException()
                            val value = sentence["text"]?.asString.orEmpty()
                            synchronized(lock) {
                                if (lines[id]?.second != true) lines[id] = value to (sentence["sentence_end"]?.asBoolean == true)
                                require(lines.size <= 512 && lines.values.sumOf { it.first.length } <= 32000)
                            }
                        }
                    }
                } catch (_: RuntimeException) { fail(TranslationIssue.SERVICE) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.get()) fail(when (response?.code) { 401, 403 -> TranslationIssue.CREDENTIAL; 429 -> TranslationIssue.LIMIT; else -> TranslationIssue.NETWORK })
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (finished.count != 0L) fail(TranslationIssue.NETWORK)
                webSocket.close(code, null)
            }
        })
        try {
            if (!ready.await(12, TimeUnit.SECONDS)) throw TranslationFailure(TranslationIssue.NETWORK)
            error.get()?.let { throw TranslationFailure(it) }
            val stride = input.rate / 16000
            var position = 0
            while (position < input.samples.size) {
                error.get()?.let { throw TranslationFailure(it) }
                if (Thread.currentThread().isInterrupted) throw TranslationFailure(TranslationIssue.INTERRUPTED)
                val n = minOf(1600, (input.samples.size - position) / stride)
                if (n == 0) break
                val bytes = ByteArray(n * 2)
                repeat(n) { i ->
                    var sum = 0
                    repeat(stride) { sum += input.samples[position++].toInt() }
                    val value = sum / stride
                    bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value shr 8).toByte()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (socket.queueSize() > 128000 && System.nanoTime() < deadline) Thread.sleep(5)
                if (socket.queueSize() > 256000 || !socket.send(bytes.toByteString())) throw TranslationFailure(TranslationIssue.NETWORK)
            }
            if (!socket.send(gson.toJson(mapOf("header" to header("finish-task"), "payload" to mapOf("input" to emptyMap<String, String>())))))
                throw TranslationFailure(TranslationIssue.NETWORK)
            if (!finished.await(30, TimeUnit.SECONDS)) throw TranslationFailure(TranslationIssue.NETWORK)
            error.get()?.let { throw TranslationFailure(it) }
            val result = synchronized(lock) {
                if (lines.isEmpty() || lines.values.any { !it.second }) throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
                lines.values.joinToString(" ") { it.first }.trim()
            }
            if (result.isBlank()) throw TranslationFailure(TranslationIssue.EMPTY_REPLY)
            return RecognizedSpeech(result, trace)
        } finally { closed.set(true); socket.cancel() }
    }
    private companion object { val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build() }
}
