package dev.polybridge.eval

import com.google.gson.JsonParser
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ProbeLimits(val handshakeMs: Long = 15000, val finalMs: Long = 20000)
data class ProbeResult(val model: String, val sentBytes: Int = 0, val elapsedMs: Long = 0,
    val connectedMs: Long? = null, val error: String? = null, val events: EventSnapshot = EventSnapshot(),
    val startedAtEpochMs: Long = 0, val requestId: String? = null)
class EvaluationProbe {
    fun run(model: Candidate, url: String, token: String, pcm16: ByteArray, target: String, limits: ProbeLimits = ProbeLimits()): ProbeResult {
        require(pcm16.size in 2..(60 * 32000) && pcm16.size % 2 == 0)
        require(limits.handshakeMs in 100..20000 && limits.finalMs in 100..30000)
        val start = System.nanoTime()
        val startedAtEpochMs = System.currentTimeMillis()
        val requestId = java.util.concurrent.atomic.AtomicReference<String?>()
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        val events = EvaluationEvents()
        val configured = AtomicBoolean()
        val ending = AtomicBoolean()
        val task = UUID.randomUUID().toString().replace("-", "")
        val client = OkHttpClient.Builder().connectTimeout(limits.handshakeMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        val socket = client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            private fun configure(webSocket: WebSocket) {
                if (configured.compareAndSet(false, true)) {
                    if (!webSocket.send(EvaluationProtocol.configure(model, target, task))) events.fail("SEND_CONFIG_FAILED")
                }
            }
            override fun onOpen(webSocket: WebSocket, response: Response) {
                (response.header("x-request-id") ?: response.header("x-dashscope-request-id"))
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,128}")) && !it.startsWith("sk-") }?.let { requestId.set(it) }
                if (model == Candidate.ASR31) configure(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ending.get()) return
                if (text.length > 256000) { events.fail("EVENT_LIMIT"); webSocket.cancel(); return }
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    if (json.str("type") == "session.created") configure(webSocket)
                    if (json.str("type") == "session.updated") {
                        val session = json.obj("session")
                        val modes = session?.getAsJsonArray(if (model == Candidate.LIVE38) "output_modalities" else "modalities")
                        if (modes != null && modes.any { it.asString != "text" }) events.fail("CONFIG_NOT_TEXT_ONLY")
                    }
                    events.accept(text, elapsed())
                    val snapshot = events.snapshot()
                    if (snapshot.responses > 8) events.fail("RESPONSE_LIMIT")
                    if (snapshot.error != null) webSocket.cancel()
                } catch (_: RuntimeException) { events.fail("INVALID_EVENT"); webSocket.cancel() }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { events.fail("UNEXPECTED_BINARY"); webSocket.cancel() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ending.get()) events.fail(response?.let { "HTTP_${it.code}" } ?: "NETWORK_${t.javaClass.simpleName}")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!ending.get() && !events.snapshot().finished) events.fail("CLOSED_$code")
            }
        })
        var sent = 0
        var connected: Long? = null
        try {
            while (!events.snapshot().ready && events.snapshot().error == null && elapsed() < limits.handshakeMs) Thread.sleep(10)
            if (!events.snapshot().ready && events.snapshot().error == null) events.fail("HANDSHAKE_TIMEOUT")
            if (events.snapshot().error == null) {
                connected = elapsed()
                val audioStart = System.nanoTime()
                while (sent < pcm16.size && events.snapshot().error == null) {
                    val end = minOf(sent + 3200, pcm16.size)
                    val part = pcm16.copyOfRange(sent, end)
                    val accepted = if (model == Candidate.ASR31) socket.send(part.toByteString())
                        else socket.send("""{"type":"input_audio_buffer.append","audio":"${Base64.getEncoder().encodeToString(part)}"}""")
                    if (!accepted || socket.queueSize() > 256000) { events.fail("SEND_AUDIO_FAILED"); break }
                    sent = end
                    val sleep = sent * 1_000_000_000L / 32000 - (System.nanoTime() - audioStart)
                    if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep)
                }
                if (events.snapshot().error == null) {
                    EvaluationProtocol.finish(model, task)?.let { if (!socket.send(it)) events.fail("SEND_FINISH_FAILED") }
                    val deadline = elapsed() + limits.finalMs
                    while (elapsed() < deadline && events.snapshot().error == null) {
                        val state = events.snapshot()
                        if (state.finished) break
                        if (!model.live && model != Candidate.ASR31 && state.pendingResponses == 0 && state.responses > 0 && state.targetFinals > 0 &&
                            state.sourceFinals > 0 && elapsed() - (state.lastTextMs ?: 0) >= 1500) break
                        Thread.sleep(20)
                    }
                    val state = events.snapshot()
                    if (model.live || model == Candidate.ASR31) {
                        if (!state.finished && state.error == null) events.fail("FINAL_TIMEOUT")
                    } else if (state.pendingResponses > 0 && state.error == null) events.fail("FINAL_TIMEOUT")
                    else if (state.responses == 0 && state.error == null) events.fail("NO_RESPONSE")
                }
            }
        } catch (_: InterruptedException) { events.fail("INTERRUPTED"); Thread.currentThread().interrupt() }
        finally {
            ending.set(true)
            socket.close(1000, null)
            socket.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        val state = events.snapshot()
        return ProbeResult(model.model, sent, elapsed(), connected, state.error, state, startedAtEpochMs, requestId.get())
    }
}
