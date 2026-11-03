package dev.polybridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.polybridge.app.*
import dev.polybridge.qwen.Region
import dev.polybridge.app.audio.typeLabel
import dev.polybridge.app.audio.Hardware
import dev.polybridge.app.audio.HeadsetRole

@Composable internal fun SettingsPanel(settings: TranslationSettings, cloud: () -> Unit, diagnostics: () -> Unit,
    inputLabel: String, selectInput: () -> Unit, hardware: Hardware, requestBluetooth: () -> Unit) {
    val config by settings.state.collectAsState()
    val routes by settings.lastRoutes.collectAsState()
    var showHeadsets by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeading("PRÉFÉRENCES", "设置", "翻译服务、声音与折叠屏")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Kicker("翻译服务")
            SettingRow(Mark.CLOUD, "千问翻译", if (config.configured) "已保存个人凭据 · ${config.cloud.region.label}" else "连接个人百炼账号，启用语音和文字翻译", cloud)
            Kicker("对话与声音")
            SettingRow(Mark.MIC, "说话时的麦克风", inputLabel, selectInput)
            SettingRow(Mark.SPEAK, "自动朗读译文", "打开对话页时，读出刚完成的译文", { settings.playback(!config.autoSpeak) }, config.autoSpeak)
            SettingRow(Mark.SPEAK, "轻声外放", "机身扬声器按最低档朗读，耳机沿用系统音量", { settings.quietSpeaker(!config.quietSpeaker) }, config.quietSpeaker)
            SettingRow(Mark.AUDIO, "中法分开播放", "连接耳机时尝试法语外放、中文进耳机，以实际路由为准", { settings.splitPlayback(!config.splitPlayback) }, config.splitPlayback)
            SettingRow(Mark.SPEAK, "手动重播优先用耳机", "点重播时，两种语言都读到耳机。关闭后按对话的分路设置播放。", { settings.privateReplay(!config.privateReplay) }, config.privateReplay)
            SettingRow(Mark.AUDIO, "我的耳机", "查看连接状态，为麦克风和两种译音选择耳机", { showHeadsets = true })
            if (routes.isNotEmpty()) Text(routes.entries.joinToString("  ·  ") { (lang, route) ->
                (if (lang.startsWith("fr")) "法语" else "中文") + "上次路由到" + typeLabel(route.type) +
                    (if (route.matchedPreference == false) "（未匹配首选）" else "")
            }, color = Muted, style = MaterialTheme.typography.bodySmall)
            SettingRow(Mark.FLIP, "对坐阅读", "展开时，法语一侧朝向对面的人", { settings.orientation(!config.opposed) }, config.opposed)
            Kicker("关于")
            SettingRow(Mark.AUDIO, "设备诊断", "测试手机、耳机与应用播放，导出报告", diagnostics)
            Text("PolyBridge   v${BuildConfig.VERSION_NAME}", Modifier.padding(vertical = 10.dp), color = Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (showHeadsets) HeadsetDialog(settings, hardware, requestBluetooth) { showHeadsets = false }
}

@Composable private fun HeadsetDialog(settings: TranslationSettings, hardware: Hardware, request: () -> Unit, close: () -> Unit) {
    val assignments by settings.headsets.assignments.collectAsState()
    var choosing by remember { mutableStateOf<HeadsetRole?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("我的耳机") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!hardware.hasBluetoothAccess) TextButton(onClick = request) { Text("允许查看附近的耳机") }
            else if (hardware.paired.isEmpty()) Text("尚未找到耳机，请先在手机蓝牙设置中配对。")
            hardware.paired.forEach { headset ->
                Text(headset.name, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(when (headset.connected) { true -> "已连接"; false -> "已配对，未连接"; null -> "已配对，连接待确认" }, color = Muted)
            }
            Spacer(Modifier.height(8.dp))
            Text("每次用一支麦克风。指定接收译音的耳机后，设备不匹配时保留字幕并静音。", color = Muted)
            HeadsetRole.entries.forEach { role ->
                val identity = assignments[role]
                val name = if (identity == null) "自动" else hardware.paired.firstOrNull { it.identity == identity }?.name ?: "已指定 · 暂不可见"
                TextButton(onClick = { choosing = if (choosing == role) null else role }) { Text(role.title + " · " + name) }
                if (choosing == role) {
                    TextButton(onClick = { settings.headsets.assign(role, null); choosing = null }) { Text("自动选择") }
                    hardware.paired.filter { it.identity != null }.forEach { headset ->
                        TextButton(onClick = {
                            settings.headsets.assign(role, headset.identity)
                            if (role == HeadsetRole.MICROPHONE) settings.microphone(Microphone.HEADSET)
                            choosing = null
                        }) { Text(headset.name) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable private fun SettingRow(mark: Mark, title: String, summary: String, action: () -> Unit, checked: Boolean? = null) {
    Row(Modifier.fillMaxWidth().background(CardWhite, RoundedCornerShape(16.dp)).clickable(role = if (checked == null) Role.Button else Role.Switch, onClick = action)
        .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(mark, Forest, 21.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        if (checked == null) Glyph(Mark.FORWARD, Muted, 17.dp) else Switch(checked, onCheckedChange = null)
    }
}

@Composable internal fun CloudDialog(settings: TranslationSettings, dismiss: () -> Unit, saved: () -> Unit) {
    val config by settings.state.collectAsState()
    var region by remember { mutableStateOf(config.cloud.region) }
    var workspace by remember { mutableStateOf(config.cloud.workspace) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(workspace.isNotBlank()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("连接千问") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("松开说话键会把这一句发给阿里云百炼识别、翻译。凭据加密保存在这台手机，服务费用按你的账号结算。", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Region.entries.forEach { item -> FilterChip(region == item, { region = item }, { Text(item.label) }) }
            }
            OutlinedTextField(key, { key = it; error = "" }, label = { Text(if (config.configured) "替换密钥（可留空）" else "百炼 API Key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起业务空间" else "指定业务空间（可选）") }
            if (advanced) OutlinedTextField(workspace, { workspace = it; error = "" }, singleLine = true,
                label = { Text("业务空间 ID") }, supportingText = { Text("留空使用本地区公共域名") })
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (config.configured) TextButton(onClick = {
                runCatching { settings.disconnect() }.onSuccess { dismiss() }.onFailure { error = "无法移除，请重试。" }
            }) { Text("移除本机密钥") }
        }
    }, confirmButton = { Button(enabled = key.isNotBlank() || config.configured, onClick = {
        runCatching { settings.save(region, workspace.trim(), key.trim()) }
            .onSuccess { key = ""; saved() }.onFailure { error = if (it is IllegalArgumentException) "请检查业务空间 ID。" else "无法安全保存凭据，请重试。" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("返回") } })
}
