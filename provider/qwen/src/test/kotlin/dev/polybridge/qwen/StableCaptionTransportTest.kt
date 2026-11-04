package dev.polybridge.qwen

import com.google.gson.JsonParser
import dev.polybridge.core.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real local HTTP/WebSocket I/O, not a claim about the cloud model's recognition accuracy. */
class StableCaptionTransportTest {
    @Test fun earlyResponseCancellationAndTextCompletionReachStableHistoryExactlyOnce() {
        MockWebServer().use { server ->
            val translations = AtomicInteger()
            val emitted = CopyOnWriteArrayList<LiveUpdate>()
            var history = Conversation()
            val stable = StableCaptions { event ->
                emitted += event
                val origin = { start: Long -> LiveOrigin("local-server", Kind.CAPTIONS, start) }
                history = when (event) {
                    is LiveUpdate.Caption -> history.heardSource(event, origin(event.startedMs))
                    is LiveUpdate.Finished -> history.heard(LiveUpdate.Caption(event.id, event.side, event.source,
                        event.target, event.startedMs), origin(event.startedMs), event.issue)
                    else -> history
                }
            }
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/api-ws/")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        var sent = false
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"session.created"}""") }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val type = JsonParser.parseString(text).asJsonObject["type"].asString
                            if (type == "session.update") webSocket.send("""{"type":"session.updated"}""")
                            if (type == "input_audio_buffer.append" && !sent) {
                                sent = true
                                listOf(
                                    """{"type":"response.created","response":{"id":"ra"}}""",
                                    """{"type":"response.text.delta","response_id":"ra","delta":"我要"}""",
                                    """{"type":"input_audio_buffer.speech_started","item_id":"a","audio_start_ms":10}""",
                                    """{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"Je ne veux pas deux billets."}""",
                                    """{"type":"input_audio_buffer.speech_started","item_id":"b","audio_start_ms":8000}""",
                                    """{"type":"response.done","response":{"id":"ra","status":"cancelled"}}""",
                                    """{"type":"response.created","response":{"id":"rb"}}""",
                                    """{"type":"conversation.item.input_audio_transcription.completed","item_id":"b","transcript":"Merci."}""",
                                    """{"type":"response.text.done","response_id":"rb","text":"谢谢。"}""",
                                    """{"type":"response.done","response":{"id":"rb","status":"completed"}}""",
                                    """{"type":"response.done","response":{"id":"rb","status":"completed"}}"""
                                ).forEach(webSocket::send)
                            }
                        }
                    })
                    translations.incrementAndGet()
                    val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                    assertEquals("qwen-mt-flash", body["model"].asString)
                    assertEquals("Je ne veux pas deux billets.", body["messages"].asJsonArray[0].asJsonObject["content"].asString)
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"model":"qwen-mt-flash","choices":[{"message":{"content":"我不要两张票。"}}]}""")
                }
            }
            val options = QwenOptions(liveModel = ModelIds.AUDIO31, feature = ModelFeature.CAPTIONS.name)
            QwenSpeech("local-fixture-only", server.url("/compatible-mode/v1/chat/completions").toUrl(), options)
                .live(true, stable::accept).use { session ->
                    session.connect(); session.send(ShortArray(1600), 1600, 16000); session.finish()
                }
            assertEquals(1, translations.get())
            assertTrue(emitted.filterIsInstance<LiveUpdate.Caption>().all { it.target.isEmpty() && it.sourceFinal })
            val restored = ReportCodec.readChat(ReportCodec.saveChat(history)).recoverInterrupted()
            assertEquals(listOf("Je ne veux pas deux billets.", "Merci."), restored.turns.map { it.sourceText })
            assertEquals(listOf("我不要两张票。", "谢谢。"), restored.turns.map { it.translatedText })
            assertTrue(restored.turns.all { it.status == TurnStatus.READY })
            assertEquals(2, emitted.filterIsInstance<LiveUpdate.Finished>().size)
        }
    }
}
