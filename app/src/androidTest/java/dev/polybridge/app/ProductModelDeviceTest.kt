package dev.polybridge.app

import android.os.Bundle
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.polybridge.core.*
import dev.polybridge.eval.*
import dev.polybridge.qwen.*
import dev.polybridge.app.audio.VoicePcm
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Opt-in, bounded fixture requests through production providers, same campaign budget, no playback. */
@RunWith(AndroidJUnit4::class)
class ProductModelDeviceTest {
    @Test(timeout = 100000) fun chosenModelReachesProductionService() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("productModels") == "true")
        val model = args.getString("productModel")!!
        require(model in setOf(ModelIds.AUDIO31, ModelIds.LIVE38, ModelIds.ASR31, ModelIds.MT_PLUS, ModelIds.TTS_INSTRUCT))
        val activity = openProjectLauncher()
        val app = activity.application as PolyBridgeApp
        val base = app.settings.state.value.cloud
        val options = base.copy(workspace = args.getString("productWorkspace") ?: base.workspace,
            utteranceModel = model, asrModel = if (model == ModelIds.ASR31) model else ModelIds.ASR,
            translationModel = if (model == ModelIds.MT_PLUS) model else ModelIds.MT,
            voiceModel = if (model == ModelIds.TTS_INSTRUCT) model else ModelIds.TTS)
        val folder = File(app.filesDir, "qwen-evaluation-20261104")
        check(folder.isDirectory) { "Existing budget ledger required" }
        val attempt = args.getString("productAttempt", "1")
        require(attempt in setOf("1", "2"))
        val runId = "product-060-" + model + if (attempt == "1") "" else "-attempt2"
        fun fixture(): ByteArray {
            val original = app.assets.open("phrasebook/tickets-fr.wav").use { it.readBytes() }
            val pcm = requireNotNull(VoicePcm.wave(original))
            val data = java.nio.ByteBuffer.wrap(original).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray((pcm.length / 2L * 16000 / pcm.rate).toInt()) {
                data.getShort(pcm.offset + (it.toLong() * pcm.rate / 16000).toInt() * 2)
            }
            return MonoWave(16000).apply { append(samples, samples.size) }.bytes()
        }
        val inputWave = fixture() // Validate fixture before reserving any paid request.
        val gson = Gson()
        fun write(file: AtomicFile, value: Any) {
            val stream = file.startWrite()
            try { stream.write(gson.toJson(value).toByteArray()); file.finishWrite(stream) }
            catch (e: Throwable) { file.failWrite(stream); throw e }
        }
        RandomAccessFile(File(folder, "campaign.lock"), "rw").use { lockFile -> lockFile.channel.tryLock().use { lock ->
            check(lock != null)
            val file = AtomicFile(File(folder, "budget.json"))
            val budget = EvaluationBudget(gson.fromJson(String(file.readFully()), BudgetState::class.java)) { write(file, it) }
            val reserve = if (model in setOf(ModelIds.AUDIO31, ModelIds.LIVE38)) 700_000L else 300_000L
            budget.reserve(runId, reserve)
            val provider = requireNotNull(app.settings.provider(options))
            val started = System.currentTimeMillis()
            val result = runCatching {
                when (model) {
                    ModelIds.AUDIO31, ModelIds.LIVE38 -> {
                        val result = provider.utterance(Side.FRENCH, inputWave, audio = true)
                        assertTrue(result.source.contains("billet", true))
                        assertTrue(result.target.contains("票"))
                        assertTrue("No generated audio", result.wave.size > 1000)
                        assertEquals(model, result.model?.requestedModel)
                        mapOf("source" to result.source, "target" to result.target, "audioBytes" to result.wave.size)
                    }
                    ModelIds.ASR31 -> {
                        val text = provider.transcribe(Side.FRENCH, inputWave)
                        assertTrue(text.contains("billet", true)); mapOf("source" to text)
                    }
                    ModelIds.MT_PLUS -> {
                        val text = provider.translate(Side.FRENCH, "Je voudrais deux billets.")
                        assertTrue(text.contains("票")); mapOf("target" to text)
                    }
                    else -> {
                        val wave = provider.synthesize("fr-FR", "Bonjour, je voudrais deux billets.")
                        assertTrue(wave.size > 1000); mapOf("audioBytes" to wave.size)
                    }
                }
            }
            budget.settle(runId, null) // No complete billing usage: retain conservative reservation.
            val report = mapOf("runId" to runId, "model" to model, "passed" to result.isSuccess,
                "durationMs" to System.currentTimeMillis() - started, "result" to result.getOrNull(),
                "trace" to provider.lastTrace,
                "diagnostic" to provider.lastFailure,
                "error" to result.exceptionOrNull()?.let { if (it is TranslationFailure) it.issue.name else it.javaClass.simpleName },
                "reservedOrEstimatedCampaignMicros" to budget.reservedMicros)
            write(AtomicFile(File(folder, "$runId.json")), report)
            inst.sendStatus(0, Bundle().apply { putString("productModel", gson.toJson(report)) })
            result.getOrThrow()
        } }
    }
}
