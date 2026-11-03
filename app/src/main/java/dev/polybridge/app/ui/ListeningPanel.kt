package dev.polybridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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
    actions: TalkActions, start: () -> Unit, stop: () -> Unit, captions: () -> Unit) {
    val state by app.live.view.collectAsState()
    val on = active?.kind == Kind.LISTEN
    val view = LocalView.current
    DisposableEffect(on, view) {
        val previous = view.keepScreenOn
        if (on) view.keepScreenOn = true
        onDispose { if (on) view.keepScreenOn = previous }
    }
    val report by app.store.report.collectAsState()
    val rows = remember(chat, state.captureId, state.kind, state.drafts, report) {
        transcriptRows(chat, state, Kind.LISTEN, report.probes.lastOrNull { it.kind == Kind.LISTEN }?.id)
    }
    val title = when {
        !on -> "听身边的人"
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
            if (rows.isNotEmpty()) {
                Kicker("监听全文  ·  ${rows.size} 段")
                TranscriptList(rows, Modifier.weight(1f), actions)
            }
            else Text(if (on) "两人轮流说话，双语字幕会出现在这里。" else
                "用麦克风听身边的对话，中文和法语自动互译。请先告诉对方你在使用翻译。离开此页会结束监听。",
                color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 9.dp)) {
            if (!on) TextButton(onClick = captions) { Text("听的是其他应用？看屏幕字幕") }
            if (state.speaking) TextButton(onClick = app.live::interrupt) { Text("打断朗读，继续说话") }
            Button(onClick = if (on) stop else start, enabled = on || active == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                if (on) InputBars(active!!.level.collectAsState().value) else Glyph(Mark.AUDIO, Paper)
                Spacer(Modifier.width(10.dp)); Text(if (on) "结束监听" else "开始监听")
            }
        }
    }
}

@Composable private fun InputBars(level: Float) {
    val value by animateFloatAsState(level, tween(80), label = "输入音量")
    Canvas(Modifier.size(28.dp).testTag("input_level").semantics { progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f) }) {
        for (i in 0..4) {
            val weight = 1f - kotlin.math.abs(2 - i) * .23f
            val half = size.height * (.055f + value * .40f * weight)
            val x = size.width * (i + .5f) / 5
            drawLine(Paper, Offset(x, center.y - half), Offset(x, center.y + half), 2.6.dp.toPx(), StrokeCap.Round)
        }
    }
}
