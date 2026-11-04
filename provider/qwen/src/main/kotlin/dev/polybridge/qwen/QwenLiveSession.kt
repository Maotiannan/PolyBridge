package dev.polybridge.qwen

import com.google.gson.JsonParser
import dev.polybridge.core.*
import okhttp3.*
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.net.URL

internal class QwenLiveSession(private val token: String, private val options: QwenOptions, private val textOnly: Boolean,
    private val update: (LiveUpdate) -> Unit, private val fixedSide: Side? = null,
    private val endpoint: URL = options.region.endpoint(options.workspace), private val held: Boolean = false) : LiveSpeechSession {
    private val routes = RealtimeProtocol.routes(options.liveModel, fixedSide)
    private val manual = held && options.liveModel != ModelIds.LIVE38
    private val ready = CountDownLatch(routes.size)
    private val ended = CountDownLatch(routes.size)
    private val stopped = AtomicBoolean()
    private val error = AtomicReference<TranslationIssue?>()
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
    private val frame = ByteArray(3200)
    private var used = 0
    private val parsers = java.util.concurrent.CopyOnWriteArrayList<RealtimeEvents>()
    private val completions = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastMessage = System.nanoTime()
    private val closing = AtomicBoolean()

    override fun connect() {
        val uri = (if (endpoint.protocol == "https") "wss" else "ws") + "://${endpoint.authority}/api-ws/v1/realtime?model=${options.liveModel}"
        val request = Request.Builder().url(uri).addHeader("Authorization", "Bearer $token").build()
        for (side in routes) {
            if (stopped.get()) throw TranslationFailure(TranslationIssue.INTERRUPTED)
            var trace = ModelTrace(options.liveModel, feature = options.feature, voice = RealtimeProtocol.voice(options.liveModel))
            var pcmRate = 24000
            val events = RealtimeEvents(side) { event ->
                if (event is LiveUpdate.Finished) completions.incrementAndGet()
                update(if (event is LiveUpdate.Finished) event.copy(pcmRate = pcmRate, model = trace) else event)
            }.apply { inferLanguage = options.liveModel != ModelIds.LIVE35 }
            parsers += events
            val listener = object : WebSocketListener() {
                var configured = false
                var remoteFinished = false
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trace = trace.copy(requestId = safeIdentifier(response.header("x-request-id") ?: response.header("x-dashscope-request-id")))
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (stopped.get()) return
                    try {
                        if (text.length > 512 * 1024) throw TranslationFailure(TranslationIssue.LIMIT)
                        lastMessage = System.nanoTime()
                        val json = JsonParser.parseString(text).asJsonObject
                        if (json["type"]?.asString in setOf("session.created", "session.updated")) {
                            json.getAsJsonObject("session")?.let { session ->
                                val actual = session["model"]?.asString?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,120}")) }
                                trace = trace.copy(actualModel = actual ?: trace.actualModel,
                                    sessionId = safeIdentifier(session["id"]?.asString) ?: trace.sessionId)
                                session.getAsJsonObject("audio")?.getAsJsonObject("output")?.getAsJsonObject("format")?.let { format ->
                                    require(format["type"]?.asString?.let { it == "pcm" } != false)
                                    pcmRate = format["sample_rate"]?.asInt ?: 24000
                                    require(pcmRate in setOf(8000, 16000, 24000, 48000))
                                }
                            }
                        }
                        when (json["type"]?.asString) {
                            "session.created" -> {
                                if (!webSocket.send(RealtimeProtocol.configure(options, textOnly, side, manual))) fail(TranslationIssue.NETWORK)
                            }
                            "session.updated" -> if (!configured) { configured = true; ready.countDown() }
                            "session.finished" -> if (!remoteFinished) { remoteFinished = true; ended.countDown() }
                            "error" -> fail(serviceIssue(json.getAsJsonObject("error")?.get("code")?.asString.orEmpty()))
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
                    if (!stopped.get() && !remoteFinished && !closing.get()) fail(TranslationIssue.NETWORK)
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (sockets.any { it.queueSize() > 256 * 1024 }) {
            if (stopped.get()) throw TranslationFailure(error.get() ?: TranslationIssue.INTERRUPTED)
            if (System.nanoTime() >= deadline) throw TranslationFailure(TranslationIssue.NETWORK)
            Thread.sleep(10)
        }
        if (sockets.any { !it.send(packet) }) throw TranslationFailure(TranslationIssue.NETWORK)
    }

    override fun finish() {
        if (stopped.get()) return
        flush()
        if (manual) {
            sockets.forEach { it.send("""{"type":"input_audio_buffer.commit"}""") }
            if (!RealtimeProtocol.translation(options.liveModel)) sockets.forEach { it.send("""{"type":"response.create"}""") }
        }
        if (RealtimeProtocol.translation(options.liveModel)) {
            sockets.forEach { it.send("""{"type":"session.finish"}""") }
            if (!ended.await(20, TimeUnit.SECONDS)) throw TranslationFailure(TranslationIssue.NETWORK)
        } else {
            // Conversational protocols have no session.finish. Flush the last VAD turn, then drain it.
            if (!manual) {
                val quiet = ShortArray(1600)
                repeat(12) { send(quiet, quiet.size, 16000); Thread.sleep(100) }
            }
            val start = System.nanoTime()
            while (!stopped.get()) {
                val now = System.nanoTime()
                if ((!manual || completions.get() > 0) && parsers.all { it.idle } && now - start >= TimeUnit.MILLISECONDS.toNanos(1500) &&
                    now - lastMessage >= TimeUnit.MILLISECONDS.toNanos(1500)) break
                if (now - start > TimeUnit.SECONDS.toNanos(25)) throw TranslationFailure(TranslationIssue.NETWORK)
                Thread.sleep(25)
            }
        }
        error.get()?.let { throw TranslationFailure(it) }
        if (parsers.any { !it.idle }) throw TranslationFailure(TranslationIssue.INTERRUPTED)
    }

    private fun fail(reason: TranslationIssue) {
        if (stopped.get() || !error.compareAndSet(null, reason)) return
        close()
        update(LiveUpdate.Stopped(reason))
    }

    override fun close() {
        stopped.set(true)
        closing.set(true)
        repeat(routes.size) { ready.countDown(); ended.countDown() }
        sockets.forEach { it.cancel() }
    }

    private companion object {
        val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
}

internal fun safeIdentifier(value: String?): String? = value?.takeIf {
    !it.startsWith("sk-") && it.matches(Regex("[A-Za-z0-9_-]{4,128}"))
}
internal fun serviceIssue(code: String): TranslationIssue = when {
    code.contains("auth", true) || code.contains("permission", true) -> TranslationIssue.CREDENTIAL
    code.contains("limit", true) || code.contains("quota", true) -> TranslationIssue.LIMIT
    else -> TranslationIssue.SERVICE
}
