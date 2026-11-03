package dev.polybridge.app.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.polybridge.app.ProbeRequest
import dev.polybridge.core.*
import java.util.Locale

@Composable fun ConversationPanel(chat: Conversation, active: ProbeRequest?, onStart: (Side) -> String?,
    onEnd: (String, Boolean) -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<TextToSpeech?>(null) }
    var voiceReady by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { voiceReady = it == TextToSpeech.SUCCESS }
        player = engine
        onDispose { engine.stop(); engine.shutdown() }
    }
    var rotate by rememberSaveable { mutableStateOf(true) }
    val begin: (Side) -> String? = { player?.stop(); onStart(it) }
    val speak: (Turn) -> Unit = { turn ->
        val engine = player
        if (!voiceReady || engine == null) onNotice("系统语音引擎还未就绪")
        else if (engine.setLanguage(Locale.forLanguageTag(turn.side.target)) < 0) onNotice("系统尚未安装此语言的声音")
        else if (engine.speak(turn.translatedText, TextToSpeech.QUEUE_FLUSH, null, turn.id) == TextToSpeech.ERROR)
            onNotice("系统播报失败")
    }
    Text("面对面 · 演示模式", style = MaterialTheme.typography.titleMedium)
    Text("按住真实收音，上滑或移出可取消。原文和译文是固定示例。", style = MaterialTheme.typography.bodySmall)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对面区域旋转 180°")
                    Switch(checked = rotate, onCheckedChange = { rotate = it })
                }
                Column(Modifier.fillMaxWidth().weight(1f).graphicsLayer { rotationZ = if (rotate) 180f else 0f }) {
                    Messages(chat, active, speak, Modifier.weight(1f))
                    HoldButton(Side.FRENCH, active, begin, onEnd)
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    Messages(chat, active, speak, Modifier.weight(1f))
                    HoldButton(Side.CHINESE, active, begin, onEnd)
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            Messages(chat, active, speak, Modifier.weight(1f))
            HoldButton(Side.FRENCH, active, begin, onEnd)
            HoldButton(Side.CHINESE, active, begin, onEnd)
        }
    }
}

@Composable private fun Messages(chat: Conversation, active: ProbeRequest?, speak: (Turn) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(vertical = 6.dp)) {
        if (chat.turns.isEmpty()) item { Text("按住对应语言的按钮，开始第一句话。") }
        items(chat.turns.asReversed(), key = { it.id }) { turn ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(if (turn.side == Side.CHINESE) "中文 → Français" else "Français → 中文", style = MaterialTheme.typography.labelMedium)
                    when (turn.status) {
                        TurnStatus.CAPTURING -> Text("正在收音…")
                        TurnStatus.FAILED -> Text(turn.verdict?.label ?: "采集未完成")
                        TurnStatus.DEMO_READY -> {
                            Text("示例原文 · ${turn.sourceText}")
                            Text("示例译文 · ${turn.translatedText}")
                            TextButton(enabled = active == null, onClick = { speak(turn) }) { Text("播报示例译文") }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HoldButton(side: Side, active: ProbeRequest?, start: (Side) -> String?,
    finish: (String, Boolean) -> Unit) {
    val press by rememberUpdatedState(start)
    val release by rememberUpdatedState(finish)
    val current by rememberUpdatedState(active)
    val held = active?.side == side
    val language = if (side == Side.CHINESE) "中文" else "Français"
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 52.dp).clip(shape)
        .background(if (held) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
        .semantics {
            role = Role.Button
            onClick(label = if (held) "松开发送" else "开始收音") {
                if (held) active?.let { release(it.id, false) } else press(side)
                true
            }
        }.pointerInput(side) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (current != null) return@awaitEachGesture
                val id = press(side) ?: return@awaitEachGesture
                val limit = 64.dp.toPx()
                var send = false
                try {
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break
                        val pos = change.position
                        if (change.isConsumed || (pos - down.position).getDistance() > limit ||
                            pos.x !in 0f..size.width.toFloat() || pos.y !in 0f..size.height.toFloat()) break
                        if (!change.pressed) { send = true; change.consume(); break }
                        change.consume()
                    }
                } finally { release(id, !send) }
            }
        }, contentAlignment = Alignment.Center) {
        Text(if (held) "正在收音 · 松开发送" else "按住说话 · $language")
    }
}
