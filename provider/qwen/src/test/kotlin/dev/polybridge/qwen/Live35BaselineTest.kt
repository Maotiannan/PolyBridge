package dev.polybridge.qwen

import com.google.gson.JsonParser
import dev.polybridge.core.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The user's usable LiveTranslate 3.5 route is the regression baseline, not Audio 3.1. */
class Live35BaselineTest {
    @Test fun previewArrivesBeforeOriginalAndReplyCompletionAcrossTheRealSocket() {
        MockWebServer().use { server ->
            val preview = CountDownLatch(1)
            val published = CopyOnWriteArrayList<LiveUpdate>()
            repeat(2) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    var sent = false
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"session.created"}""")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (JsonParser.parseString(text).asJsonObject["type"].asString) {
                            "session.update" -> webSocket.send("""{"type":"session.updated"}""")
                            "input_audio_buffer.append" -> if (!sent) {
                                sent = true
                                listOf(
                                    """{"type":"input_audio_buffer.speech_started","item_id":"a","audio_start_ms":0}""",
                                    """{"type":"conversation.item.input_audio_transcription.text","item_id":"a","language":"fr","text":"Je voudrais","stash":""}""",
                                    """{"type":"response.created","response":{"id":"ra"}}""",
                                    """{"type":"conversation.item.created","previous_item_id":"a","item":{"id":"oa","role":"assistant","content":[]}}""",
                                    """{"type":"response.text.delta","response_id":"ra","item_id":"oa","delta":"我想买"}"""
                                ).forEach(webSocket::send)
                            }
                        }
                    }
                }))
            }
            QwenSpeech("local-fixture-only", server.url("/").toUrl(), QwenOptions(liveModel = ModelIds.LIVE35,
                feature = ModelFeature.CAPTIONS.name)).live(true) { event ->
                published += event
                if (event is LiveUpdate.Caption && event.target == "我想买") preview.countDown()
            }.use { session ->
                session.connect()
                session.send(ShortArray(1600), 1600, 16000)
                assertTrue("the local client must not hold previews until completion", preview.await(3, TimeUnit.SECONDS))
                assertFalse(published.any { it is LiveUpdate.Finished })
                assertFalse(published.filterIsInstance<LiveUpdate.Caption>().any { it.sourceFinal })
            }
        }
    }

    @Test fun twoConnectionsKeepAllThreeMessagesAcrossFiveReplaysAndLateItemLinkage() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    var round = 0
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"session.created","session":{"model":"${ModelIds.LIVE35}"}}""")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (JsonParser.parseString(text).asJsonObject["type"].asString) {
                            "session.update" -> webSocket.send("""{"type":"session.updated"}""")
                            "session.finish" -> webSocket.send("""{"type":"session.finished"}""")
                            "input_audio_buffer.append" -> if (round < 5) {
                                val group = round++
                                // Three originals, then responses 1/3/2. The third starts as two provisional words.
                                for (number in 1..3) {
                                    val id = "s$group-$number"
                                    val at = group * 60000 + number * 10000
                                    webSocket.send("""{"type":"input_audio_buffer.speech_started","item_id":"$id","audio_start_ms":$at}""")
                                    webSocket.send("""{"type":"conversation.item.input_audio_transcription.text","item_id":"$id","language":"fr","text":"Je voudrais","stash":""}""")
                                    webSocket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id","language":"fr","transcript":"Message $number complet, répétition $group."}""")
                                }
                                for (number in listOf(1, 3, 2)) {
                                    val id = "s$group-$number"
                                    webSocket.send("""{"type":"response.created","response":{"id":"r$id"}}""")
                                    webSocket.send("""{"type":"response.text.done","response_id":"r$id","item_id":"o$id","text":"第${group}轮完整第${number}段。"}""")
                                    webSocket.send("""{"type":"response.done","response":{"id":"r$id","status":"completed"}}""")
                                    webSocket.send("""{"type":"conversation.item.created","previous_item_id":"$id","item":{"id":"o$id","role":"assistant","content":[]}}""")
                                }
                            }
                        }
                    }
                }))
            }
            val published = CopyOnWriteArrayList<LiveUpdate>()
            QwenSpeech("local-fixture-only", server.url("/").toUrl(), QwenOptions(liveModel = ModelIds.LIVE35,
                feature = ModelFeature.CAPTIONS.name)).live(true, published::add).use { session ->
                session.connect()
                repeat(5) { session.send(ShortArray(1600), 1600, 16000) }
                session.finish()
            }
            val finals = published.filterIsInstance<LiveUpdate.Finished>()
            assertEquals(15, finals.size)
            assertTrue(finals.all { it.issue == null && it.side == Side.FRENCH })
            assertTrue(published.filterIsInstance<LiveUpdate.Caption>().any { it.source == "Je voudrais" })
            for (round in 0..4) for (number in 1..3) {
                val line = finals.single { it.id == "s$round-$number" }
                assertEquals("Message $number complet, répétition $round.", line.source)
                assertEquals("第${round}轮完整第${number}段。", line.target)
            }
        }
    }
}
