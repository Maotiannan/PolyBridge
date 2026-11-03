package dev.polybridge.core

import com.google.gson.GsonBuilder
import java.security.MessageDigest

data class DeviceFacts(val model: String, val os: String, val android: String, val sdk: Int,
    val patch: String, val fingerprintHash: String, val appVersion: String)

fun fingerprintHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

data class ProbeReport(
    val device: DeviceFacts, val probes: List<ProbeEntry> = emptyList(), val lastTone: ToneResult? = null,
    val schemaVersion: Int = 1, val simultaneousBluetoothOutput: Capability = Capability.UNVERIFIED,
    val independentBluetoothMics: Capability = Capability.UNVERIFIED
)

object ReportCodec {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    fun json(report: ProbeReport): String = gson.toJson(report)
    fun read(text: String): ProbeReport = gson.fromJson(text, ProbeReport::class.java)
    fun saveChat(chat: Conversation): String = gson.toJson(chat)
    fun readChat(text: String): Conversation = gson.fromJson(text, Conversation::class.java)

    fun markdown(report: ProbeReport): String = buildString {
        appendLine("# PolyBridge 音频诊断")
        with(report.device) {
            appendLine("$model · $os · Android $android (API $sdk) · App $appVersion")
            appendLine("安全补丁 $patch · 构建指纹 SHA-256 `$fingerprintHash`")
        }
        appendLine()
        for (p in report.probes) {
            appendLine("## ${p.kind} · ${p.verdict} · ${p.id}")
            appendLine("启动时间（Unix 毫秒）${p.startedAtMs} · 目标 ${p.target.label} ${p.targetVersion.orEmpty()}")
            appendLine("时长 ${p.elapsedMs} ms · ${p.sampleRateHz ?: "?"} Hz · 声道 ${p.channels ?: "?"}")
            appendLine("请求输入 ${p.requestedInputId ?: "自动"} · 实际输入 ${p.inputRoute ?: "未观测"}")
            appendLine("样本 ${p.signal.samples} · 非零样本 ${p.signal.nonZeroSamples} · 系统静音 ${p.systemSilenced ?: "未知"}")
            appendLine("RMS ${p.signal.rmsDbfs ?: "—"} dBFS · 峰值 ${p.signal.peakDbfs ?: "—"} dBFS")
            p.errorCode?.let { appendLine("原因 $it") }
        }
        appendLine()
        appendLine("最近一次测试音 ${report.lastTone ?: "未测试"}")
        appendLine("双耳同时播放 ${report.simultaneousBluetoothOutput} · 独立双麦 ${report.independentBluetoothMics}")
    }
}
