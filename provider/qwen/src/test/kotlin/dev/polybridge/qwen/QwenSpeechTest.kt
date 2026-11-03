package dev.polybridge.qwen

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.util.Base64

class QwenSpeechTest {
    @Test fun sendsSpokenWaveAndTranslatesTheReturnedWordsInBothDirections() {
        for (side in Side.entries) {
            val source = if (side == Side.CHINESE) "火车九点开。" else "Il reste deux places."
            val target = if (side == Side.CHINESE) "Le train part à neuf heures." else "还剩两个座位。"
            val bodies = mutableListOf<String>()
            val headers = mutableListOf<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/chat/completions") { exchange ->
                bodies += exchange.requestBody.bufferedReader().use { it.readText() }
                headers += exchange.requestHeaders.getFirst("Authorization")
                val result = if (bodies.size == 1) source else target
                val json = """{"choices":[{"message":{"content":"$result"}}]}""".toByteArray()
                exchange.sendResponseHeaders(200, json.size.toLong())
                exchange.responseBody.use { it.write(json) }
            }
            server.start()
            try {
                val api = QwenSpeech("test-only", URL("http://127.0.0.1:${server.address.port}/chat/completions"))
                val clip = MonoWave(16_000).apply { append(shortArrayOf(-32768, 32767, 9), 2) }.bytes()
                assertEquals(source, api.transcribe(side, clip))
                assertEquals(target, api.translate(side, source))
                assertEquals(listOf("Bearer test-only", "Bearer test-only"), headers)
                val asr = JsonParser.parseString(bodies.first()).asJsonObject
                val audio = asr["messages"].asJsonArray[0].asJsonObject["content"].asJsonArray[0].asJsonObject
                assertEquals("input_audio", audio["type"].asString)
                val wave = Base64.getDecoder().decode(audio["input_audio"].asJsonObject["data"].asString.substringAfter(','))
                assertArrayEquals(clip, wave)
                assertEquals("RIFF", String(wave, 0, 4, Charsets.US_ASCII))
                assertEquals(48, wave.size)
                assertArrayEquals(byteArrayOf(0, -128, -1, 127), wave.copyOfRange(44, 48))
                assertEquals(side.source.substringBefore('-'), asr["asr_options"].asJsonObject["language"].asString)
                val mt = JsonParser.parseString(bodies.last()).asJsonObject
                assertEquals(source, mt["messages"].asJsonArray[0].asJsonObject["content"].asString)
                val languages = mt["translation_options"].asJsonObject
                assertEquals(if (side == Side.CHINESE) "French" else "Chinese", languages["target_lang"].asString)
                assertEquals(if (side == Side.CHINESE) "Chinese" else "French", languages["source_lang"].asString)
            } finally { server.stop(0) }
        }
    }

    @Test fun HTTPFailuresAndUnreadableRepliesBecomeActionableWithoutLeakingContent() {
        for ((code, issue) in listOf(401 to TranslationIssue.CREDENTIAL, 429 to TranslationIssue.LIMIT,
            503 to TranslationIssue.SERVICE, 200 to TranslationIssue.EMPTY_REPLY)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val body = "private-text".toByteArray()
                exchange.sendResponseHeaders(code, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val api = QwenSpeech("test-only", URL("http://127.0.0.1:${server.address.port}/"))
                val failure = assertThrows(TranslationFailure::class.java) { api.translate(Side.CHINESE, "原文") }
                assertEquals(issue, failure.issue)
                assertFalse(failure.toString().contains("private-text"))
            } finally { server.stop(0) }
        }
    }
}
