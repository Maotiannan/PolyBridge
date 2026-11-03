package dev.polybridge.app.ui

import android.media.AudioDeviceInfo
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.polybridge.app.ProbeRequest
import dev.polybridge.app.audio.*
import dev.polybridge.core.*
import java.util.Locale

@Composable fun DiagnosticsPanel(hardware: Hardware, report: ProbeReport, active: ProbeRequest?,
    input: Int?, output: Int?, target: PlaybackTarget, toneBusy: Boolean,
    onInput: (Int?) -> Unit, onOutput: (Int?) -> Unit, onTarget: (PlaybackTarget) -> Unit, onBluetooth: () -> Unit,
    onMic: () -> Unit, onPlayback: () -> Unit, onStop: () -> Unit, onTone: () -> Unit,
    onJson: () -> Unit, onMarkdown: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("设备总览", "麦克风", "播放捕获", "联调报告")
    val idle = active == null && !toneBusy
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, title -> FilterChip(selected = i == tab, onClick = { tab = i }, label = { Text(title) }) }
    }
    key(tab) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tabs[tab], style = MaterialTheme.typography.headlineSmall)
        active?.let {
            val sampling by it.sampling.collectAsState()
            Text(when {
                !sampling -> "正在准备音频设备…"
                it.kind == Kind.PLAYBACK -> "正在捕获「${it.target.label}」的声音"
                else -> "麦克风采样中"
            })
            OutlinedButton(onClick = onStop) { Text("停止采样") }
        }
        when (tab) {
            0 -> {
                Text("${report.device.model} · Android ${report.device.android}（API ${report.device.sdk}）")
                Text("系统版本 ${report.device.os}\n安全补丁 ${report.device.patch}")
                HorizontalDivider()
                Text("系统列出的声音设备", style = MaterialTheme.typography.titleMedium)
                hardware.ports.forEach { port ->
                    Text("${if (port.isSource) "输入" else "输出"} · ${port.route().label()}")
                }
                Text("已配对设备", style = MaterialTheme.typography.titleMedium)
                if (!hardware.hasBluetoothAccess) OutlinedButton(onClick = onBluetooth) { Text("授权查看已配对设备") }
                else if (hardware.paired.isEmpty()) Text("没有可见的已配对设备")
                hardware.paired.forEach {
                    Text("${it.name} · ${when (it.connected) { true -> "音频协议已连接"; false -> "音频协议未连接"; null -> "连接状态未知" }}")
                }
                Text("实际收音和播放设备在测试运行时读取。")
            }
            1 -> {
                Text("选择输入设备，点开始后说一句话。10 秒后显示音量、系统静音状态和实际收音设备。")
                PortPicker("输入", hardware.ports.filter { it.isSource }, input, onInput, idle)
                Button(enabled = idle, onClick = onMic) { Text("开始 10 秒麦克风测试") }
                HorizontalDivider()
                Text("播放一声短音，请留意声音实际从哪里传出。")
                PortPicker("输出", hardware.ports.filter { it.isSink }, output, onOutput, idle)
                OutlinedButton(enabled = idle, onClick = onTone) { Text("播放测试音") }
                report.lastTone?.let { Text("测试音 ${if (it.played) "已播放" else "播放失败"} · 实际输出 ${it.actualOutput.label()}") }
                report.probes.lastOrNull { it.kind == Kind.MICROPHONE }?.let { ResultDetails(it) }
            }
            2 -> {
                Text("先选本应用测试音做对照。测试语音消息时，开始后在目标应用播放一段已知内容。每次测试都需要系统授权，10 秒后自动停止。")
                PlaybackTarget.entries.forEach { item ->
                    FilterChip(selected = target == item, enabled = idle, onClick = { onTarget(item) }, label = { Text(item.label) })
                }
                Button(enabled = idle, onClick = onPlayback) { Text("申请授权并测试 10 秒") }
                report.probes.lastOrNull { it.kind == Kind.PLAYBACK }?.let { ResultDetails(it) }
            }
            3 -> {
                Text("仅导出设备版本、声音统计和路由。最近 40 次测试保存在本机。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onJson) { Text("导出 JSON") }
                    OutlinedButton(onClick = onMarkdown) { Text("导出 Markdown") }
                }
                Text("双耳同时播放、两路独立收音、微信与 WhatsApp 通话双向翻译均待真机验证。")
                if (report.probes.isEmpty()) Text("还没有测试记录。")
                report.probes.asReversed().forEach { HorizontalDivider(); ResultDetails(it) }
            }
        }
        Spacer(Modifier.height(12.dp))
    } }
}

@Composable private fun PortPicker(label: String, ports: List<AudioDeviceInfo>, selected: Int?, onSelect: (Int?) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { open = true }) {
            Text("$label · " + (ports.find { it.id == selected }?.route()?.label() ?: if (selected == null) "系统自动选择" else "设备已断开"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("系统自动选择") }, onClick = { onSelect(null); open = false })
            ports.forEach { DropdownMenuItem(text = { Text(it.route().label()) }, onClick = { onSelect(it.id); open = false }) }
        }
    }
}

@Composable private fun ResultDetails(p: ProbeEntry) {
    fun db(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
    Text("${p.kind.label} · ${p.verdict.label}", style = MaterialTheme.typography.titleMedium)
    Text("${p.elapsedMs} 毫秒 · ${p.signal.samples} 个样本 · 实际输入 ${p.inputRoute.label()}")
    Text("平均 ${db(p.signal.rmsDbfs)} dBFS · 峰值 ${db(p.signal.peakDbfs)} dBFS")
    Text("系统静音 ${when (p.systemSilenced) { true -> "是"; false -> "否"; null -> "无法读取" }}")
    p.errorCode?.let {
        val message = when (it) {
            "TARGET_NOT_INSTALLED" -> "手机上未找到所选应用"
            "PROJECTION_CONSENT", "NO_PROJECTION", "NO_CONSENT" -> "未收到系统的播放共享授权"
            "RECORD_AUDIO", "AUDIO_PERMISSION", "SERVICE_PERMISSION" -> "系统未允许本次录音"
            "ROUTE_REQUEST_REJECTED" -> "系统拒绝切换到所选的收音设备"
            "COMMUNICATION_BUSY" -> "系统正在使用通话通道，请在通话结束后重试"
            "NO_MATCHING_HEADSET" -> "无法确认对应的耳机，请检查蓝牙通话连接"
            "HEADSET_SETUP_TIMEOUT" -> "等待耳机通话连接超时，请重新连接后再试"
            "FOREGROUND_START", "SERVICE_START" -> "无法启动采样，请返回应用后重试"
            else -> "音频设备未能完成采样，详细原因见导出报告"
        }
        Text(message)
    }
}
