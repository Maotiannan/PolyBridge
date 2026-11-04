package dev.polybridge.qwen

import com.google.gson.JsonParser
import dev.polybridge.core.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class LiveModelTransportTest {
    @Test fun finishingSendsEnoughSilenceForTheConfiguredCaptionPause() {
        MockWebServer().use { server ->
            val frames = java.util.concurrent.atomic.AtomicInteger()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"session.created"}""") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (JsonParser.parseString(text).asJsonObject["type"].asString) {
                        "session.update" -> webSocket.send("""{"type":"session.updated"}""")
                        "input_audio_buffer.append" -> frames.incrementAndGet()
                    }
                }
            }))
            val options = QwenOptions(liveModel = ModelIds.AUDIO31, mediaPauseMs = 2200, feature = ModelFeature.CAPTIONS.name)
            QwenLiveSession("fixture", options, true, {}, endpoint = server.url("/").toUrl()).use {
                it.connect(); it.finish()
            }
            assertTrue("收尾静音不足以触发配置的断句时间", frames.get() * 100 >= 2200)
        }
    }
    @Test fun manualFinishWaitsForLateFirstResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"type":"session.created"}""") }
                override fun onMessage(ws: WebSocket, text: String) {
                    val type = JsonParser.parseString(text).asJsonObject["type"].asString
                    if (type == "session.update") ws.send("""{"type":"session.updated"}""")
                    if (type == "response.create") {
                        Thread.sleep(2100)
                        listOf(
                            """{"type":"input_audio_buffer.committed","item_id":"a"}""",
                            """{"type":"response.created","response":{"id":"r"}}""",
                            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"Bonjour."}""",
                            """{"type":"response.text.done","response_id":"r","text":"你好。"}""",
                            """{"type":"response.done","response":{"id":"r","status":"completed"}}"""
                        ).forEach(ws::send)
                    }
                }
            }))
            val results = CopyOnWriteArrayList<LiveUpdate>()
            QwenLiveSession("fixture", QwenOptions(liveModel = ModelIds.AUDIO31), true, results::add,
                Side.FRENCH, server.url("/").toUrl(), held = true).use {
                it.connect(); it.send(ShortArray(3200), 3200, 16000); it.finish()
                assertEquals(1, results.filterIsInstance<LiveUpdate.Finished>().size)
            }
        }
    }
    @Test fun allFourModelsUseRealWebSocketConfigurationAndFinishTheSameUtterance() {
        for (model in ModelCatalog.realtime) MockWebServer().use { server ->
            val packets = CopyOnWriteArrayList<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"type":"session.created","session":{"id":"sess_fixture","model":"${model.id}"}}""") }
                var emitted = false
                override fun onMessage(ws: WebSocket, text: String) {
                    packets += text
                    val type = JsonParser.parseString(text).asJsonObject["type"].asString
                    if (type == "session.update") ws.send("""{"type":"session.updated","session":{"model":"${model.id}"}}""")
                    if (type == "input_audio_buffer.append" && !emitted) {
                        emitted = true
                        listOf(
                            """{"type":"input_audio_buffer.speech_started","item_id":"a"}""",
                            """{"type":"response.created","response":{"id":"r"}}""",
                            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","language":"fr","transcript":"Bonjour."}""",
                            """{"type":"response.text.done","response_id":"r","text":"你好。"}""",
                            """{"type":"response.done","response":{"id":"r","status":"completed"}}"""
                        ).forEach(ws::send)
                    }
                    if (type == "session.finish") ws.send("""{"type":"session.finished"}""")
                }
            }))
            val results = CopyOnWriteArrayList<LiveUpdate>()
            val session = QwenLiveSession("test-only", QwenOptions(liveModel = model.id), true, results::add,
                fixedSide = Side.FRENCH, endpoint = server.url("/").toUrl())
            session.use { it.connect(); it.send(ShortArray(1600), 1600, 16000); it.finish() }
            assertEquals("你好。", results.filterIsInstance<LiveUpdate.Finished>().single().target)
            assertEquals(model.id, results.filterIsInstance<LiveUpdate.Finished>().single().model?.actualModel)
            val config = JsonParser.parseString(packets.first()).asJsonObject["session"].asJsonObject
            assertEquals("text", config[if (model.id == ModelIds.LIVE38) "output_modalities" else "modalities"].asJsonArray.single().asString)
            assertTrue(server.takeRequest().path!!.contains(model.id))
        }
    }
}
