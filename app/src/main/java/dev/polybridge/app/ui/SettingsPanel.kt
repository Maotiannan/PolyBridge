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
            SettingRow(Mark.CLOUD, "千问与模型", if (config.configured) "${config.models.label} · ${config.cloud.region.label}" else "选择整套或分功能模型，连接百炼账号", cloud)
            Kicker("对话与声音")
            SettingRow(Mark.MIC, "说话时的麦克风", inputLabel, selectInput)
            SettingRow(Mark.SPEAK, "自动朗读译文", "打开对话页时，读出刚完成的译文", { settings.playback(!config.autoSpeak) }, config.autoSpeak)
            SettingRow(Mark.AUDIO, "中法分开播放", "连接耳机时尝试法语外放、中文进耳机，以实际路由为准", { settings.splitPlayback(!config.splitPlayback) }, config.splitPlayback)
            SettingRow(Mark.SPEAK, "手动重播优先用耳机", "点重播时，两种语言都读到耳机。关闭后按对话的分路设置播放。", { settings.privateReplay(!config.privateReplay) }, config.privateReplay)
            SettingRow(Mark.AUDIO, "我的耳机", "查看连接状态，为麦克风和两种译音选择耳机", { showHeadsets = true })
            if (routes.isNotEmpty()) Text(routes.entries.joinToString("  ·  ") { (lang, route) ->
                (if (lang.startsWith("fr")) "法语" else "中文") + "上次路由到" + typeLabel(route.type) +
                    (if (route.matchedPreference == false) "（未匹配首选）" else "")
            }, color = Muted, style = MaterialTheme.typography.bodySmall)
            SettingRow(Mark.FLIP, "对坐阅读", "展开时，法语一侧朝向对面的人", { settings.orientation(!config.opposed) }, config.opposed)
            Kicker("关于")
            SettingRow(Mark.FLIP, "面对面沉浸模式", "收起系统栏，轻扫先显示系统手势", { settings.immersiveTalk(!config.immersiveTalk) }, config.immersiveTalk)
            SettingRow(Mark.AUDIO, "设备诊断", "测试手机、耳机与应用播放，导出报告", diagnostics)
            Text("PolyBridge   v${BuildConfig.VERSION_NAME}", Modifier.padding(vertical = 10.dp), color = Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (showHeadsets) HeadsetDialog(settings, hardware, requestBluetooth) { showHeadsets = false }
}

@Composable private fun HeadsetDialog(settings: TranslationSettings, hardware: Hardware, request: () -> Unit, close: () -> Unit) {
    val assignments by settings.headsets.assignments.collectAsState()
    var choosing by remember { mutableStateOf<HeadsetRole?>(null) }
    StudioDialog(onDismissRequest = close, title = { Text("我的耳机") }, text = {
        Column {
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
    var advanced by remember { mutableStateOf(!config.configured) }
    var models by remember { mutableStateOf(config.models) }
    StudioDialog(onDismissRequest = dismiss, title = { Text("千问与模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            config.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ModelSettings(models) { models = it; error = "" }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起账号连接" else "账号连接 · ${region.label}") }
            if (advanced) {
            Text("语音和文字发送到百炼处理。密钥加密保存在本机，费用按你的账号结算。", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Region.entries.forEach { item -> FilterChip(region == item, { region = item }, { Text(item.label) }) }
            }
            OutlinedTextField(key, { key = it; error = "" }, Modifier.fillMaxWidth(), label = { Text(if (config.configured) "替换密钥（可留空）" else "百炼 API Key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(workspace, { workspace = it; error = "" }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("业务空间 ID") }, supportingText = { Text("留空使用本地区公共域名") })
            if (config.configured) TextButton(onClick = {
                runCatching { settings.disconnect() }.onSuccess { dismiss() }.onFailure { error = "无法移除，请重试。" }
            }) { Text("移除本机密钥") }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(enabled = key.isNotBlank() || config.configured, onClick = {
        runCatching { settings.save(region, workspace.trim(), key.trim(), models) }
            .onSuccess { key = ""; saved() }.onFailure { error = if (it is IllegalArgumentException) it.message ?: "请检查账号和模型设置。" else "无法安全保存凭据，请重试。"; advanced = true }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("返回") } })
}
