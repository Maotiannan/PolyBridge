package dev.polybridge.app

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.polybridge.app.audio.VoicePcm
import dev.polybridge.eval.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit opt-in only. No microphone, player, private chat reads, preference writes, or key export. */
@RunWith(AndroidJUnit4::class)
class QwenEvaluationDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val gson = Gson()
    private fun report(value: Any) = inst.sendStatus(0, Bundle().apply { putString("qwenEvaluation", gson.toJson(value)) })

    @Test(timeout = 110000) fun fixedAudioProbe() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qwenEvaluation") == "true")
        val context = inst.targetContext
        val app = context.applicationContext as PolyBridgeApp
        val options = app.settings.state.value.cloud
        val region = options.region.name
        val selection = args.getString("evalModel", "ACCOUNT")
        if (selection == "ACCOUNT") {
            report(mapOf("configured" to app.settings.state.value.configured, "region" to region,
                "workspaceConfigured" to options.workspace.isNotBlank(), "appVersion" to BuildConfig.VERSION_NAME))
            return
        }
        if (selection == "LEDGER") { evaluate(context, app, selection, args); return }
        // OriginOS may suspend background socket operations even under instrumentation.
        // Foreground the existing app for the test; this window flag is not a saved setting.
        val activity = openProjectLauncher()
        val keepDisplay = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        inst.runOnMainSync { activity.window.addFlags(keepDisplay) }
        try { evaluate(context, app, selection, args) }
        finally { inst.runOnMainSync { activity.window.clearFlags(keepDisplay) } }
    }

    private fun evaluate(context: Context, app: PolyBridgeApp, selection: String, args: Bundle) {
        val options = app.settings.state.value.cloud
        val region = options.region.name
        check(app.settings.state.value.configured) { "ACCOUNT_NOT_CONFIGURED" }
        val workspace = args.getString("evalWorkspace") ?: options.workspace
        val host = options.region.endpoint(workspace).host // validates before reading credentials or reserving
        val folder = File(context.filesDir, "qwen-evaluation-20261104").apply { mkdirs() }
        RandomAccessFile(File(folder, "campaign.lock"), "rw").use { lockFile ->
            lockFile.channel.tryLock().use { lock ->
                check(lock != null) { "EVALUATION_ALREADY_RUNNING" }
                val ledger = AtomicFile(File(folder, "budget.json"))
                val initial = if (ledger.baseFile.exists() || File(ledger.baseFile.path + ".bak").exists())
                    gson.fromJson(String(ledger.readFully()), BudgetState::class.java) else BudgetState()
                val budget = EvaluationBudget(initial) { next -> atomicWrite(ledger, gson.toJson(next)) }
                // Reconcile earlier campaign estimates without deleting results or silently resetting budget.
                for (entry in initial.entries.filter { (it.actualMicros ?: 0) > 0 }) {
                    val file = File(folder, "${entry.id}.json")
                    if (!file.exists()) continue
                    val previous = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
                    val probe = gson.fromJson(previous.getAsJsonObject("result"), ProbeResult::class.java)
                    val priorModel = Candidate.entries.single { it.model == probe.model }
                    if (priorModel.costMicros(probe.events.usage, previous.get("region").asString == "SINGAPORE") == null)
                        budget.revokeEstimate(entry.id, "UNPRICED_INPUT_TEXT")
                }
                if (selection == "LEDGER") {
                    // Explicit human observation for this campaign only, not automatic forgiveness.
                    if (args.getString("evalNegligibleFirstBatch") == "true") {
                        for ((id, amount) in mapOf("live38-fr-01" to 300_000L, "omni38-fr-01" to 200_000L, "live35-fr-01" to 500_000L)) {
                            if (initial.entries.single { it.id == id }.previousReservationMicros == null)
                                budget.reviseReservation(id, amount, "USER_REPORTED_NEGLIGIBLE_20261104")
                        }
                    }
                    report(mapOf("reservedOrEstimatedCampaignMicros" to budget.reservedMicros,
                        "ledger" to if (ledger.baseFile.exists()) gson.fromJson(String(ledger.readFully()), BudgetState::class.java) else initial))
                    return
                }
                val model = Candidate.valueOf(selection)
                val fixture = args.getString("evalFixture", "tickets-fr")
                require(fixture in setOf("tickets-fr", "tickets-zh", "train-fr", "train-zh", "breathing-fr", "pause15-fr"))
                val runId = requireNotNull(args.getString("evalRunId"))
                require(runId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
                val pcm = fixturePcm(context, fixture)
                require(pcm.size <= 60 * 32000)
                val token = credential(context)
                // Conservative reservation, not a provider-enforced spending cap. Unknown usage retains it.
                val reservation = if (model.live) 3_000_000L else 2_000_000L
                budget.reserve(runId, reservation)
                val path = if (model == Candidate.ASR31) "/api-ws/v1/inference" else "/api-ws/v1/realtime?model=${model.model}"
                val url = "wss://$host$path"
                val result = EvaluationProbe().run(model, url, token, pcm, if (fixture.endsWith("fr")) "zh" else "fr")
                // Only acknowledged completed usage can release a reservation. A rejected handshake had no input.
                val zero = result.sentBytes == 0 && result.error?.startsWith("HTTP_") == true
                val complete = result.error == null && result.events.usageComplete &&
                    (result.events.finished || result.events.responses > 0)
                val actual = when { zero -> 0L; complete -> model.costMicros(result.events.usage, region == "SINGAPORE"); else -> null }
                budget.settle(runId, actual)
                val record = mapOf("runId" to runId, "fixture" to fixture, "region" to region,
                    "workspaceConfigured" to workspace.isNotBlank(), "workspaceOverride" to args.containsKey("evalWorkspace"),
                    "inputMs" to pcm.size / 32,
                    "result" to result, "estimatedMicros" to actual,
                    "pricingIssue" to if (complete && actual == null) "UNPRICED_INPUT_TEXT" else null,
                    "reservedOrEstimatedCampaignMicros" to budget.reservedMicros)
                atomicWrite(AtomicFile(File(folder, "$runId.json")), gson.toJson(record))
                report(record)
            }
        }
    }

    private fun atomicWrite(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }

    private fun credential(context: Context): String {
        // Same Android Keystore record as the installed app; plaintext exists only in this phone process.
        val encoded = requireNotNull(context.getSharedPreferences("translator", Context.MODE_PRIVATE).getString("credential", null))
        val (iv, body) = encoded.split('.', limit = 2)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("polybridge.personal.translation", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun fixturePcm(context: Context, fixture: String): ByteArray {
        val raw = if (fixture == "pause15-fr") EvaluationAudio.withPause(
            rawFixturePcm(context, "tickets-fr"), rawFixturePcm(context, "station-fr"), 15000)
        else rawFixturePcm(context, fixture)
        // 300 ms leading and 2.4 s trailing silence, identical for every candidate; no speaker output.
        return ByteArray(4800 * 2) + raw + ByteArray(38400 * 2)
    }

    private fun rawFixturePcm(context: Context, fixture: String): ByteArray {
        val wave = if (fixture == "breathing-fr") inst.context.assets.open("rain/breathing-fr.wav").use { it.readBytes() }
            else context.assets.open("phrasebook/$fixture.wav").use { it.readBytes() }
        val pcm = requireNotNull(VoicePcm.wave(wave))
        require(pcm.channels == 1)
        val input = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        val count = (pcm.length / 2L * 16000 / pcm.rate).toInt()
        val output = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { output.putShort(input.getShort(pcm.offset + (it.toLong() * pcm.rate / 16000).toInt() * 2)) }
        return output.array()
    }
}
