package dev.polybridge.qwen

import com.google.gson.JsonParser
import dev.polybridge.core.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class StreamingAsrTest {
    @Test fun binaryAudioUsesSelectedAsrAndReplacesPartialSentences() {
        MockWebServer().use { server ->
            var received = 0
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { received += bytes.size }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JsonParser.parseString(text).asJsonObject
                    if (json["header"].asJsonObject["action"].asString == "run-task") {
                        assertEquals(ModelIds.ASR31, json["payload"].asJsonObject["model"].asString)
                        webSocket.send("""{"header":{"event":"task-started"}}""")
                    } else {
                        for ((id, words, final) in listOf(Triple(1, "Je veux", false), Triple(1, "Je veux deux billets.", true), Triple(2, "Où est la gare ?", true)))
                            webSocket.send("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":$id,"text":"$words","sentence_end":$final}}}}""")
                        webSocket.send("""{"header":{"event":"task-finished"}}""")
                    }
                }
            }))
            val result = StreamingAsr("test-only", QwenOptions(asrModel = ModelIds.ASR31), server.url("/").toUrl())
                .recognize(waveFromPcm(16000, ByteArray(6400)), Side.FRENCH)
            assertEquals("Je veux deux billets. Où est la gare ?", result.text)
            assertEquals(6400, received)
        }
    }
}
