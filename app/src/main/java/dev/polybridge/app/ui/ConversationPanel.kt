package dev.polybridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.polybridge.app.ProbeRequest
import dev.polybridge.core.*

internal val Side.label get() = if (this == Side.CHINESE) "中文" else "Français"

internal class TalkActions(val start: (Side) -> String?, val end: (String, Boolean) -> Unit,
    val speak: (Turn) -> Unit, val edit: (Turn) -> Unit, val type: (Side) -> Unit)

@Composable internal fun CompactTalk(chat: Conversation, active: ProbeRequest?, actions: TalkActions, book: () -> Unit) {
    val visible = chat.currentTurns
    Column(Modifier.fillMaxSize()) {
        PageHeading("FACE À FACE", "面对面")
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val showCover = visible.isEmpty() && maxHeight >= 260.dp
                Column(Modifier.fillMaxSize()) {
                    if (showCover) GreetingCover(book)
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("本次对话", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { actions.type(Side.CHINESE) }) {
                            Glyph(Mark.TYPE, Muted, 17.dp); Spacer(Modifier.width(6.dp)); Text("打字", color = Muted)
                        }
                    }
                    if (visible.isEmpty()) {
                        HorizontalDivider(color = Line)
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 2.dp)) {
                            Text("按住下方语言键，开始第一句。", color = Muted, style = MaterialTheme.typography.bodyMedium)
                            Text("Maintenez votre langue pour parler.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    } else Timeline(visible, null, actions, Modifier.weight(1f))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Glyph(Mark.AUDIO, Muted, 14.dp)
                Text(if (active == null) "按住说话 · 松开翻译" else "松开翻译 · 滑出取消",
                    color = Muted, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (side in Side.entries) HoldButton(side, active, actions, Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun GreetingCover(book: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(Round).background(Forest).clickable(role = Role.Button, onClick = book)) {
        Arches(Leaf.copy(alpha = .32f), Modifier.align(Alignment.CenterEnd).fillMaxWidth(.50f).height(152.dp))
        Column(Modifier.padding(horizontal = 21.dp, vertical = 16.dp)) {
            Kicker("ZH  ⇄  FR", Leaf)
            Spacer(Modifier.height(3.dp))
            Text("你好。", color = Paper, fontSize = 36.sp, lineHeight = 43.sp, fontWeight = FontWeight.SemiBold)
            Text("Bonjour.", color = Leaf, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                fontSize = 38.sp, lineHeight = 42.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("离线常用表达", color = Paper, style = MaterialTheme.typography.labelMedium)
                Glyph(Mark.OPEN, Paper, 13.dp)
            }
        }
    }
}

@Composable internal fun FoldTalk(chat: Conversation, active: ProbeRequest?, opposed: Boolean, actions: TalkActions,
    flip: () -> Unit, navigate: (Page) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SidePane(Side.FRENCH, chat, active, actions, Modifier.fillMaxWidth().weight(1f)
            .graphicsLayer { rotationZ = if (opposed) 180f else 0f })
        Row(Modifier.fillMaxWidth().background(Forest).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("PolyBridge", color = Paper, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp)); Kicker("FACE À FACE", Leaf)
            Spacer(Modifier.weight(1f))
            ToolButton(Mark.FLIP, "翻转对面方向", flip, Paper)
            ToolButton(Mark.BOOK, "常用表达", { navigate(Page.BOOK) }, Paper)
            ToolButton(Mark.HISTORY, "历史", { navigate(Page.HISTORY) }, Paper)
            ToolButton(Mark.SETTINGS, "设置", { navigate(Page.SETTINGS) }, Paper)
        }
        SidePane(Side.CHINESE, chat, active, actions, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable private fun SidePane(side: Side, chat: Conversation, active: ProbeRequest?, actions: TalkActions, modifier: Modifier) {
    val chinese = side == Side.CHINESE
    val visible = chat.currentTurns
    Box(modifier.background(if (chinese) Paper else Color(0xFFFBF1E9))) {
        if (visible.isEmpty()) Arches(if (chinese) Line else Color(0xFFE5CCBB),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.37f))
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker(if (chinese) "ZH  /  中文 → FR" else "FR  /  FRANÇAIS → ZH", if (chinese) Muted else Clay)
                Spacer(Modifier.weight(1f))
                ToolButton(Mark.TYPE, if (chinese) "打字" else "Écrire", { actions.type(side) })
            }
            if (visible.isEmpty()) Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(if (chinese) "你好。" else "Bonjour.", fontSize = 38.sp, lineHeight = 44.sp,
                    fontFamily = if (chinese) FontFamily.Default else FontFamily.Serif,
                    fontStyle = if (chinese) FontStyle.Normal else FontStyle.Italic, color = if (chinese) Forest else Clay)
                Text(if (chinese) "从你这边按住说话" else "Maintenez le bouton pour parler.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
            } else Timeline(visible, side, actions, Modifier.weight(1f))
            HoldButton(side, active, actions, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

@Composable private fun HoldButton(side: Side, active: ProbeRequest?, actions: TalkActions,
    modifier: Modifier, compact: Boolean = false) {
    val latest by rememberUpdatedState(actions)
    val current by rememberUpdatedState(active)
    val held = active?.side == side
    val sampling = active?.sampling?.collectAsState()?.value == true
    val chinese = side == Side.CHINESE
    val bg = if (chinese) Forest else Peach
    val fg = if (chinese) Paper else Clay
    val hint = when {
        held && !sampling -> if (chinese) "正在准备…" else "Un instant…"
        held -> if (chinese) "松开发送" else "Relâchez"
        chinese -> "按住说话"
        else -> "Maintenez"
    }
    Row(modifier.heightIn(min = if (compact) 55.dp else 70.dp).clip(RoundedCornerShape(18.dp))
        .background(if (held && chinese) Color(0xFF28594B) else bg)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = if (chinese) "中文，按住说话" else "Français, maintenir pour parler"
            stateDescription = hint
            onClick(if (held) "松开发送" else "开始说话") {
                if (held) active?.let { latest.end(it.id, false) } else if (current == null) latest.start(side)
                true
            }
        }.pointerInput(side) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (current != null) return@awaitEachGesture
                val id = latest.start(side) ?: return@awaitEachGesture
                var send = false
                try {
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.find { it.id == down.id } ?: break
                        val p = change.position
                        if (change.isConsumed || (p - down.position).getDistance() > 64.dp.toPx() ||
                            p.x !in 0f..size.width.toFloat() || p.y !in 0f..size.height.toFloat()) break
                        if (!change.pressed) { send = true; change.consume(); break }
                        change.consume()
                    }
                } finally { latest.end(id, !send) }
            }
        }.padding(horizontal = 13.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 34.dp else 37.dp).background(if (chinese) Leaf else Color(0xFFE9C8B6), CircleShape),
            contentAlignment = Alignment.Center) { Glyph(if (held && sampling) Mark.AUDIO else Mark.MIC, if (chinese) Forest else Clay, 19.dp) }
        Spacer(Modifier.width(9.dp))
        if (compact) {
            Text(side.label, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f)); Text(hint, color = fg, style = MaterialTheme.typography.bodyMedium)
        } else Column {
            Text(side.label, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(hint, color = fg, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable internal fun Timeline(turns: List<Turn>, viewer: Side?, actions: TalkActions, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(turns.lastOrNull()?.id) { if (state.firstVisibleItemIndex <= 1) state.scrollToItem(0) }
    LazyColumn(modifier.fillMaxWidth(), state = state, reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(turns.asReversed(), key = { it.id }) { TurnCard(it, viewer, actions) }
    }
}

@Composable internal fun TurnCard(turn: Turn, viewer: Side?, actions: TalkActions, remove: (() -> Unit)? = null) {
    val chinese = turn.side == Side.CHINESE
    val frenchUi = viewer == Side.FRENCH
    val color = if (chinese) Forest else Clay
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(CardWhite).border(1.dp, Line, RoundedCornerShape(15.dp))
        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(6.dp))
            Kicker(if (chinese) "ZH  →  FR" else "FR  →  ZH", color)
            if (turn.status == TurnStatus.DEMO_READY) Text("   旧版示例", color = Muted, style = MaterialTheme.typography.labelSmall)
            if (remove != null) { Spacer(Modifier.weight(1f)); ToolButton(Mark.DELETE, "删除这句 · ${turn.sourceText}", remove, Muted) }
        }
        Spacer(Modifier.height(5.dp))
        val ready = turn.status == TurnStatus.READY || turn.status == TurnStatus.DEMO_READY
        if (ready) {
            val originalFirst = viewer == turn.side
            Text(if (originalFirst) turn.sourceText else turn.translatedText, color = Ink,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Text(if (originalFirst) turn.translatedText else turn.sourceText, color = Muted, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { actions.speak(turn) }) { Glyph(Mark.SPEAK, color, 16.dp); Spacer(Modifier.width(5.dp)); Text(if (frenchUi) "Écouter" else "重播", color = color) }
                if (turn.status == TurnStatus.READY) TextButton(onClick = { actions.edit(turn) }) { Text(if (frenchUi) "Corriger" else "纠错", color = Muted) }
            }
        } else {
            if (turn.sourceText.isNotBlank()) Text(turn.sourceText, color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(when (turn.status) {
                TurnStatus.CAPTURING -> if (frenchUi) "Écoute en cours…" else "正在收音…"
                TurnStatus.TRANSCRIBING -> if (frenchUi) "Transcription…" else "正在识别原文…"
                TurnStatus.TRANSLATING -> if (frenchUi) "Traduction…" else "正在翻译…"
                else -> issueText(turn, frenchUi)
            }, color = Muted, style = MaterialTheme.typography.bodyMedium)
            if (turn.status == TurnStatus.FAILED && turn.sourceText.isNotBlank()) TextButton(onClick = { actions.edit(turn) }) {
                Text(if (frenchUi) "Réessayer" else "用原文重试")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun issueText(turn: Turn, fr: Boolean) = when (turn.issue) {
    TranslationIssue.CREDENTIAL -> if (fr) "Vérifiez la clé et la région." else "请到设置检查千问密钥和地区。"
    TranslationIssue.LIMIT -> if (fr) "Quota atteint. Réessayez plus tard." else "千问调用额度或频率已达上限，稍后再试。"
    TranslationIssue.NETWORK -> if (fr) "Connexion interrompue." else "无法连接千问，请检查网络。"
    TranslationIssue.EMPTY_REPLY -> if (fr) "Aucune parole reconnue." else "没识别到内容，请靠近麦克风再说一次。"
    TranslationIssue.SERVICE -> if (fr) "Service indisponible." else "千问未完成这次请求，请检查服务配置后重试。"
    TranslationIssue.INTERRUPTED -> if (fr) "Session interrompue." else "上次处理被中断。"
    null -> if (fr) "Prise de son interrompue. Réessayez." else turn.verdict?.label ?: "这句话未完成。"
}
