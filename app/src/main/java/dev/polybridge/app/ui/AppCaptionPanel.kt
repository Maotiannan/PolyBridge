package dev.polybridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.polybridge.app.*
import dev.polybridge.app.audio.CaptionApp
import dev.polybridge.app.audio.captionApps
import dev.polybridge.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun AppCaptionPanel(app: PolyBridgeApp, active: ProbeRequest?,
    start: (String) -> Unit, stop: () -> Unit, cloud: () -> Unit) {
    val context = LocalContext.current
    val config by app.settings.state.collectAsState()
    val state by app.live.view.collectAsState()
    val report by app.store.report.collectAsState()
    val chat by app.store.chat.collectAsState()
    val rows = remember(chat, state.captureId, state.kind, state.drafts, report) {
        transcriptRows(chat, state, Kind.CAPTIONS, report.probes.lastOrNull { it.kind == Kind.CAPTIONS }?.id)
    }
    val apps by produceState<List<CaptionApp>?>(null, context) { value = withContext(Dispatchers.IO) { captionApps(context) } }
    val selected = apps?.firstOrNull { it.pkg == config.captionApp } ?: apps?.firstOrNull()
    var picker by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val running = active?.kind == Kind.CAPTIONS
    Column(Modifier.fillMaxSize()) {
        PageHeading("SOUS-TITRES", "屏幕字幕")
        val info = if (rows.isEmpty()) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier
        Column(info.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isEmpty()) Column(Modifier.fillMaxWidth().background(Forest, Round).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Kicker("中文  ⇄  FRANÇAIS", Leaf)
                Text("听其他应用，看双语字幕", color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("播放语音或视频，屏幕上会逐句出现原文和译文。听到法语就译成中文，听到中文就译成法语。", color = Paper)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Kicker("声音来源"); Text(selected?.name ?: "选择播放语音的应用", color = Ink, style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(enabled = !running && !apps.isNullOrEmpty(), onClick = { picker = true; query = "" }) { Text("换应用") }
            }
            if (!running) report.probes.lastOrNull { it.kind == Kind.CAPTIONS }?.let { recent ->
                if (recent.verdict !in setOf(Verdict.SIGNAL, Verdict.CANCELLED))
                    Text(when (recent.verdict) {
                        Verdict.DENIED -> "上次未获得系统授权。开启时请允许屏幕共享和悬浮字幕。"
                        Verdict.SILENT, Verdict.NO_DATA, Verdict.SYSTEM_SILENCED -> "上次没有收到媒体声音。部分语音或通话会被来源应用限制共享。"
                        Verdict.REVOKED -> "上次字幕已随系统共享结束。"
                        else -> "上次字幕连接中断，可重新开启。"
                    }, color = Muted)
            }
            if (rows.isEmpty()) Text("首次开启请允许悬浮窗，按提示共享所选应用。开始播放后，点字幕条的「全文」可回看前文，点「停止」结束。普通电话和部分聊天语音会限制共享。", color = Muted, style = MaterialTheme.typography.bodyMedium)
            else Kicker((if (running) "本次字幕" else "上次字幕") + "  ·  ${rows.size} 段")
        }
        if (rows.isNotEmpty()) TranscriptList(rows, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp))
        Button(onClick = { if (running) stop() else if (!config.configured) cloud() else selected?.let { app.settings.captionApp(it.pkg); start(it.pkg) } },
            enabled = running || active == null && selected != null, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp).heightIn(min = 52.dp)) {
            Text(if (running) "停止屏幕字幕" else "从应用播放中采集")
        }
    }
    if (picker) StudioDialog(onDismissRequest = { picker = false }, title = { Text("在哪个应用里听？") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索应用") })
            apps.orEmpty().filter { it.name.contains(query, true) || it.pkg.contains(query, true) }.forEach { item ->
                ChoiceRow(item.name, item.pkg == selected?.pkg, { app.settings.captionApp(item.pkg); picker = false })
            }
        }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text("关闭") } })
}
