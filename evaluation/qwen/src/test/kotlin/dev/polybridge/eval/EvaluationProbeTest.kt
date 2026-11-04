package dev.polybridge.eval

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class EvaluationProbeTest {
    @Test fun handshakeRejectionDoesNotSendAudioOrLeakCredential() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val result = EvaluationProbe().run(Candidate.LIVE35, server.url("/").toString(), "secret-test-only", ByteArray(3200), "zh", ProbeLimits(handshakeMs = 1000, finalMs = 1000))
            assertEquals("HTTP_403", result.error)
            assertEquals(0, result.sentBytes)
            assertFalse(result.toString().contains("secret-test-only"))
        }
    }

    @Test fun realWebsocketDrainsFinalTextAndUsageAfterFinish() {
        MockWebServer().use { server ->
            var audioPackets = 0
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
                override fun onMessage(socket: WebSocket, text: String) {
                    when {
                        text.contains("session.update") -> socket.send("""{"type":"session.updated","session":{"modalities":["text"]}}""")
                        text.contains("input_audio_buffer.append") -> audioPackets++
                        text.contains("session.finish") -> {
                            socket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"Bonjour."}""")
                            socket.send("""{"type":"response.text.done","item_id":"b","text":"你好。"}""")
                            socket.send("""{"type":"response.done","response":{"id":"r","status":"completed","usage":{"input_tokens":10,"output_tokens":3,"input_tokens_details":{"audio_tokens":10},"output_tokens_details":{"text_tokens":3}}}}""")
                            socket.send("""{"type":"session.finished"}""")
                        }
                    }
                }
                override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, null) }
            }))
            val result = EvaluationProbe().run(Candidate.LIVE35, server.url("/").toString(), "test", ByteArray(6400), "zh", ProbeLimits(handshakeMs = 1000, finalMs = 1000))
            assertNull(result.error)
            assertEquals(6400, result.sentBytes)
            assertEquals(2, audioPackets)
            assertEquals(listOf("你好。"), result.events.targets)
            assertTrue(result.events.usageComplete)
        }
    }

    @Test fun serverWithoutConfiguredAcknowledgementTimesOutWithoutAudio() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
                override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, null) }
            }))
            val result = EvaluationProbe().run(Candidate.AUDIO31, server.url("/").toString(), "test", ByteArray(3200), "zh", ProbeLimits(handshakeMs = 200, finalMs = 200))
            assertEquals("HANDSHAKE_TIMEOUT", result.error)
            assertEquals(0, result.sentBytes)
        }
    }
}
