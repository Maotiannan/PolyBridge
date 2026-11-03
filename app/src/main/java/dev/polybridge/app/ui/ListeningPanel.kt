package dev.polybridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.polybridge.app.PolyBridgeApp
import dev.polybridge.app.ProbeRequest
import dev.polybridge.core.*

@Composable internal fun ListeningPanel(app: PolyBridgeApp, chat: Conversation, active: ProbeRequest?,
    actions: TalkActions, start: () -> Unit, stop: () -> Unit) {
    val state by app.live.view.collectAsState()
    val on = active?.kind == Kind.LISTEN
    val view = LocalView.current
    DisposableEffect(on, view) {
        val previous = view.keepScreenOn
        if (on) view.keepScreenOn = true
        onDispose { if (on) view.keepScreenOn = previous }
    }
    val turns = chat.turns.filter { it.id in state.turnIds }
    val title = when {
        !on -> "轮流说，自在聊。"
        state.reconnecting -> "正在重新连接…"
        !state.ready -> "正在连接千问…"
        state.speaking -> "正在朗读译文"
        else -> "正在听你们说话"
    }
    Column(Modifier.fillMaxSize()) {
        PageHeading("INTERPRÈTE EN DIRECT", "自由监听")
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
            Box(Modifier.fillMaxWidth().background(Forest, Round)) {
                Arches(Leaf.copy(alpha = .26f), Modifier.align(Alignment.CenterEnd).width(100.dp).height(108.dp))
                Column(Modifier.padding(18.dp)) {
                    Kicker("ZH  ⇄  FR", Leaf)
                    Spacer(Modifier.height(4.dp))
                    Text(title, color = Paper, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (on) "Sous-titres en direct" else "中文和法语自动识别", color = Leaf, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.issue?.let { issue ->
                Text(when(issue) {
                    TranslationIssue.CREDENTIAL -> "服务未授权，请检查千问设置。"
                    TranslationIssue.LIMIT -> "实时服务额度不足或请求过于频繁。"
                    TranslationIssue.NETWORK -> "网络中断，已结束监听。可重新开始。"
                    else -> "实时服务未能继续，已结束监听。"
                }, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 7.dp))
            }
            Spacer(Modifier.height(8.dp))
            state.partial?.let { line ->
                Kicker(if (line.side == Side.CHINESE) "中文  →  FR" else "FR  →  中文")
                Text(line.source, color = Muted, style = MaterialTheme.typography.bodyMedium)
                Text(line.target.ifBlank { "…" }, color = Ink, style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
            }
            if (turns.isNotEmpty()) Timeline(turns, null, actions, Modifier.weight(1f))
            else Text(if (on) "两人轮流说话，双语字幕会出现在这里。" else
                "开始后，本机麦克风会向千问的中、法两个翻译方向传送声音。请让对方知道你在使用翻译。离开此页会结束监听。",
                color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 9.dp)) {
            if (state.speaking) TextButton(onClick = app.live::interrupt) { Text("打断朗读，继续说话") }
            Button(onClick = if (on) stop else start, enabled = on || active == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Glyph(Mark.AUDIO, Paper); Spacer(Modifier.width(10.dp)); Text(if (on) "结束监听" else "开始监听")
            }
        }
    }
}
