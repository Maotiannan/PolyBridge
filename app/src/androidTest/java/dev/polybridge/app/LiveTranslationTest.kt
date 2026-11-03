package dev.polybridge.app

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.VoiceData
import dev.polybridge.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LiveTranslationTest {
    @Test fun twentyRecordedPhrasesInBothDirections() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveQwen") == "true")
        openProjectLauncher()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PolyBridgeApp
        val api = requireNotNull(app.settings.provider()) { "请先在设置中配置千问" }
        val namespace = "qa-" + UUID.randomUUID()
        val store = ProbeStore(context, namespace)
        val controller = ConversationTranslator(store, CoroutineScope(Dispatchers.Unconfined)) { api }
        val phrases = context.assets.open("phrasebook/catalog.tsv").bufferedReader().use { it.readLines() }.take(10)
        try {
            for (row in phrases) for (side in Side.entries) {
                val slug = row.substringBefore('\t')
                val lang = side.source.substringBefore('-')
                val bytes = context.assets.open("phrasebook/$slug-$lang.wav").use { it.readBytes() }
                val wave = phoneFormat(bytes)
                val request = store.begin(Kind.HOLD, PlaybackTarget.SELF, null, side, BuildConfig.VERSION_NAME)!!
                store.finish(request.empty(Verdict.SIGNAL))
                val start = SystemClock.elapsedRealtime()
                controller.recorded(request.id, wave)
                val turn = store.chat.value.turns.last()
                assertEquals("$slug $lang ${turn.issue}", TurnStatus.READY, turn.status)
                assertTrue(turn.sourceText.isNotBlank())
                assertTrue(turn.translatedText.isNotBlank())
                log("utterance", "$slug | $lang | ${turn.sourceText} | ${turn.translatedText} | ${SystemClock.elapsedRealtime() - start} ms")
            }
            assertEquals(20, ProbeStore(context, namespace).chat.value.turns.count { it.status == TurnStatus.READY })
        } finally { context.deleteSharedPreferences(namespace) }
    }

    @Test fun bothVoicesReturnPlayableSpeech() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveQwen") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        val api = requireNotNull(app.settings.provider())
        for ((language, text) in listOf("fr-FR" to "Bonjour.", "zh-CN" to "你好。")) {
            val audio = api.synthesize(language, text)
            log("synthesized", "$language | ${audio.size} bytes")
            val route = play(audio)
            log("voice", "$language | $route")
        }
    }

    /** The fixed catalog uses simple 24 kHz mono PCM. Exercise the same 16 kHz WAV writer as the microphone. */
    private fun phoneFormat(wav: ByteArray): ByteArray {
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getShort(22).toInt() == 1 && header.getShort(34).toInt() == 16)
        require(String(wav, 36, 4, Charsets.US_ASCII) == "data")
        val inputRate = header.getInt(24)
        val frames = (wav.size - 44) / 2
        val samples = ShortArray(frames * 16000 / inputRate) { index ->
            val offset = index.toLong() * inputRate / 16000
            header.getShort(44 + 2 * offset.toInt())
        }
        return MonoWave(16000).apply { append(samples, samples.size) }.bytes()
    }

    private fun play(bytes: ByteArray): String {
        val done = CountDownLatch(1)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(VoiceData(bytes))
            player.setOnCompletionListener { done.countDown() }
            player.prepare()
            assertTrue("短句播放时长异常", player.duration in 150..15_000)
            player.start()
            SystemClock.sleep(120)
            val route = player.routedDevice?.type
            assertTrue("播放未完成", done.await(15, TimeUnit.SECONDS))
            return "duration=${player.duration} ms, routeType=$route"
        } finally { player.release() }
    }

    private fun log(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })
    }
}
