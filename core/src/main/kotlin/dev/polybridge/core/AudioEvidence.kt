package dev.polybridge.core

import kotlin.math.abs
import kotlin.math.log10

enum class Kind(val label: String) {
    MICROPHONE("麦克风"), PLAYBACK("应用播放"), HOLD("按住说话"), LISTEN("自由监听"), MESSAGE("语音消息"), CAPTIONS("屏幕字幕");
    val playbackCapture get() = this == PLAYBACK || this == MESSAGE || this == CAPTIONS
    val streaming get() = this == LISTEN || this == CAPTIONS
}
enum class PlaybackTarget(val label: String, val packageName: String?) {
    SELF("应用测试音", null), WECHAT("微信", "com.tencent.mm"),
    WHATSAPP("WhatsApp", "com.whatsapp"), WHATSAPP_BUSINESS("WhatsApp Business", "com.whatsapp.w4b"), SELECTED("所选应用", null)
}
enum class Capability { UNVERIFIED, SUPPORTED, UNSUPPORTED }
enum class End { TIMED, RELEASED, CANCELLED, DENIED, PROJECTION_STOPPED, ROUTE_LOST, READ_ERROR, INTERRUPTED }
enum class Verdict(val label: String) {
    SIGNAL("测到有效声音"), NO_DATA("没有读到样本"), SILENT("读到样本，声音接近静音"),
    SYSTEM_SILENCED("系统报告收音被静音"), INCOMPLETE("采样不完整"), CANCELLED("已取消"),
    DENIED("未获授权"), REVOKED("系统已结束共享"), ROUTE_LOST("选择的设备已断开"),
    ROUTE_MISMATCH("有声音，但来自其他设备"), ROUTE_UNKNOWN("有声音，实际输入设备未知"),
    FAILED("采集失败"), INTERRUPTED("会话中断")
}

data class SignalStats(val samples: Long = 0, val nonZeroSamples: Long = 0,
    val rmsDbfs: Double? = null, val peakDbfs: Double? = null)

class SignalMeter {
    private var count = 0L
    private var nonZero = 0L
    private var energy = 0.0
    private var peak = 0

    fun add(buffer: ShortArray, length: Int) {
        require(length in 0..buffer.size)
        for (i in 0 until length) {
            val value = buffer[i].toInt()
            energy += value.toDouble() * value
            peak = maxOf(peak, abs(value))
            if (value != 0) nonZero++
        }
        count += length
    }

    fun snapshot() = SignalStats(count, nonZero,
        if (energy > 0) 10 * log10(energy / count / (32768.0 * 32768.0)) else null,
        if (peak > 0) 20 * log10(peak / 32768.0) else null)
}

fun assess(stats: SignalStats, expectedSamples: Long, systemSilenced: Boolean?, end: End): Verdict {
    return when (end) {
        End.CANCELLED -> Verdict.CANCELLED
        End.DENIED -> Verdict.DENIED
        End.PROJECTION_STOPPED -> Verdict.REVOKED
        End.ROUTE_LOST -> Verdict.ROUTE_LOST
        End.READ_ERROR -> Verdict.FAILED
        End.INTERRUPTED -> Verdict.INTERRUPTED
        End.TIMED, End.RELEASED -> when {
            systemSilenced == true -> Verdict.SYSTEM_SILENCED
            stats.samples == 0L -> Verdict.NO_DATA
            stats.samples < expectedSamples * 0.9 -> Verdict.INCOMPLETE
            (stats.peakDbfs ?: Double.NEGATIVE_INFINITY) < -60 -> Verdict.SILENT
            else -> Verdict.SIGNAL
        }
    }
}

data class Route(val type: Int, val portId: Int)
fun qualifyRoute(verdict: Verdict, requestedPort: Int?, observed: Route?): Verdict = when {
    verdict != Verdict.SIGNAL || requestedPort == null -> verdict
    observed == null -> Verdict.ROUTE_UNKNOWN
    observed.portId != requestedPort -> Verdict.ROUTE_MISMATCH
    else -> verdict
}

data class ProbeEntry(
    val id: String, val kind: Kind, val target: PlaybackTarget, val startedAtMs: Long, val verdict: Verdict,
    val requestedInputId: Int? = null, val inputRoute: Route? = null, val sampleRateHz: Int? = null,
    val channels: Int? = null, val elapsedMs: Long = 0, val systemSilenced: Boolean? = null,
    val signal: SignalStats = SignalStats(), val targetVersion: String? = null,
    val errorCode: String? = null
)
data class ToneResult(val actualOutput: Route?, val requestedId: Int?, val played: Boolean, val atMs: Long)
