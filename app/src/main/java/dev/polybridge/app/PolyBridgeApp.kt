package dev.polybridge.app

import android.app.Application
import android.content.Context
import android.os.Build
import dev.polybridge.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.polybridge.app.audio.TranslatedAudioShare
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PolyBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch { TranslatedAudioShare(this@PolyBridgeApp).clean() }
    }
    val store by lazy { ProbeStore(this) }
    val settings by lazy { TranslationSettings(this) }
    internal val clips by lazy { dev.polybridge.app.audio.VoiceClips() }
    internal val live by lazy { dev.polybridge.app.audio.LiveDesk(this, store, settings, clips) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val translator by lazy { ConversationTranslator(store, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2)), settings::provider) }
}

data class ProbeRequest(val id: String, val kind: Kind, val target: PlaybackTarget, val inputId: Int?,
    val side: Side?, val startedAt: Long, val targetVersion: String?, val end: AtomicReference<End?> = AtomicReference(null),
    val sourcePackage: String? = null) {
    val sampling = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    fun empty(verdict: Verdict, reason: String? = null) = ProbeEntry(id, kind, target, startedAt,
        verdict, requestedInputId = inputId, targetVersion = targetVersion, errorCode = reason)
}

class ProbeStore(context: Context, preferencesName: String = "diagnostics") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
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

    @Synchronized fun begin(kind: Kind, target: PlaybackTarget, inputId: Int?, side: Side?, targetVersion: String?, sourcePackage: String? = null): ProbeRequest? {
        if (current.value != null) return null
        if (kind == Kind.CAPTIONS && (sourcePackage.isNullOrBlank() || side != null)) return null
        val request = ProbeRequest(UUID.randomUUID().toString(), kind, target, inputId, side, System.currentTimeMillis(), targetVersion, sourcePackage = sourcePackage)
        if (side != null) conversation.value = conversation.value.press(request.id, side)
        current.value = request
        persist()
        return request
    }

    @Synchronized fun finish(entry: ProbeEntry) {
        val request = current.value ?: return
        if (request.id != entry.id) return
        currentReport.value = append(currentReport.value, entry)
        if (request.side != null) conversation.value = conversation.value.captureEnded(entry.id, entry.verdict)
        current.value = null
        persist()
    }

    @Synchronized fun tone(result: ToneResult) {
        currentReport.value = currentReport.value.copy(lastTone = result)
        persist()
    }

    fun changeChat(update: (Conversation) -> Conversation): Boolean = changeChat(false, update)

    @Synchronized fun changeChat(durable: Boolean, update: (Conversation) -> Conversation): Boolean {
        val next = update(conversation.value)
        if (next == conversation.value) return false
        if (!persist(next, durable)) return false
        conversation.value = next
        return true
    }

    @Synchronized fun clearHistory(): Conversation? {
        if (current.value != null || conversation.value.turns.isEmpty()) return null
        val previous = conversation.value
        return if (changeChat(durable = true) { Conversation().newSession() }) previous else null
    }

    private fun append(report: ProbeReport, entry: ProbeEntry) =
        report.copy(probes = (report.probes + entry).takeLast(40))

    private fun persist(chat: Conversation = conversation.value, durable: Boolean = false): Boolean {
        // A process death between press and release leaves a recoverable interruption.
        val diskReport = current.value?.let { append(currentReport.value, it.empty(Verdict.INTERRUPTED)) } ?: currentReport.value
        val write = prefs.edit().putString("report", ReportCodec.json(diskReport))
            .putString("chat", ReportCodec.saveChat(chat))
        if (durable) return write.commit()
        write.apply()
        return true
    }
}
