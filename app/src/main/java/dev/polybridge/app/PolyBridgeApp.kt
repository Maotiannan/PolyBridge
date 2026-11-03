package dev.polybridge.app

import android.app.Application
import android.content.Context
import android.os.Build
import dev.polybridge.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PolyBridgeApp : Application() {
    val store by lazy { ProbeStore(this) }
}

data class ProbeRequest(val id: String, val kind: Kind, val target: PlaybackTarget, val inputId: Int?,
    val side: Side?, val startedAt: Long, val targetVersion: String?, val end: AtomicReference<End?> = AtomicReference(null)) {
    fun empty(verdict: Verdict, reason: String? = null) = ProbeEntry(id, kind, target, startedAt,
        verdict, requestedInputId = inputId, targetVersion = targetVersion, errorCode = reason)
}

class ProbeStore(context: Context) {
    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val device = DeviceFacts(Build.MODEL, Build.DISPLAY, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        Build.VERSION.SECURITY_PATCH, fingerprintHash(Build.FINGERPRINT), BuildConfig.VERSION_NAME)
    private val currentReport = MutableStateFlow(runCatching {
        ReportCodec.read(prefs.getString("report", null) ?: "").takeIf { it.schemaVersion == 1 && it.device == device }
    }.getOrNull() ?: ProbeReport(device))
    private val conversation = MutableStateFlow(runCatching {
        ReportCodec.readChat(prefs.getString("chat", null) ?: "").recoverInterrupted()
    }.getOrElse { Conversation() })
    private val current = MutableStateFlow<ProbeRequest?>(null)
    val report = currentReport.asStateFlow()
    val chat = conversation.asStateFlow()
    val active = current.asStateFlow()

    @Synchronized fun begin(kind: Kind, target: PlaybackTarget, inputId: Int?, side: Side?, targetVersion: String?): ProbeRequest? {
        if (current.value != null) return null
        val request = ProbeRequest(UUID.randomUUID().toString(), kind, target, inputId, side, System.currentTimeMillis(), targetVersion)
        if (side != null) conversation.value = conversation.value.press(request.id, side)
        current.value = request
        persist()
        return request
    }

    @Synchronized fun finish(entry: ProbeEntry) {
        val request = current.value ?: return
        if (request.id != entry.id) return
        currentReport.value = append(currentReport.value, entry)
        if (request.side != null) conversation.value = conversation.value.finish(entry.id, entry.verdict, DemoTranslation)
        current.value = null
        persist()
    }

    @Synchronized fun tone(result: ToneResult) {
        currentReport.value = currentReport.value.copy(lastTone = result)
        persist()
    }

    private fun append(report: ProbeReport, entry: ProbeEntry) =
        report.copy(probes = (report.probes + entry).takeLast(40))

    private fun persist() {
        // A process death between press and release leaves a recoverable interruption.
        val diskReport = current.value?.let { append(currentReport.value, it.empty(Verdict.INTERRUPTED)) } ?: currentReport.value
        prefs.edit().putString("report", ReportCodec.json(diskReport))
            .putString("chat", ReportCodec.saveChat(conversation.value)).apply()
    }
}
