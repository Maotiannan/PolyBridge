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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.polybridge.app.ProbeRequest
import dev.polybridge.app.audio.ConversationVoice
import dev.polybridge.core.*

internal val Side.label get() = if (this == Side.CHINESE) "中文" else "Français"

internal class TalkActions(val start: (Side) -> String?, val end: (String, Boolean) -> Unit,
    val speak: (Turn) -> Unit, val edit: (Turn) -> Unit, val type: (Side) -> Unit,
    val playback: ConversationVoice.Feedback? = null, val phoneSpeak: ((Turn) -> Unit)? = null,
    val model: String? = null)

@Composable internal fun CompactTalk(chat: Conversation, active: ProbeRequest?, actions: TalkActions,
    book: () -> Unit, live: () -> Unit, call: () -> Unit) {
    val visible = chat.currentTurns
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth()) {
            PageHeading("FACE À FACE", "面对面")
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                TextButton(onClick = live) { Text("自由监听") }
                TextButton(onClick = call) { Text("中文转法语") }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
            actions.model?.let { ModelStatus(it, active != null, active?.modelOptions?.utteranceModel) }
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
            ToolButton(Mark.AUDIO, "自由监听", { navigate(Page.LIVE) }, Paper)
            ToolButton(Mark.TALK, "中文转法语", { navigate(Page.CALL) }, Paper)
            ToolButton(Mark.BOOK, "常用表达", { navigate(Page.BOOK) }, Paper)
            ToolButton(Mark.HISTORY, "历史", { navigate(Page.HISTORY) }, Paper)
            TextButton(onClick = { navigate(Page.TOOLS) }) { Text("屏幕字幕", color = Paper) }
            ToolButton(Mark.SETTINGS, "设置", { navigate(Page.SETTINGS) }, Paper)
        }
        SidePane(Side.CHINESE, chat, active, actions, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.fillMaxWidth().height(maxOf(48.dp, WindowInsets.mandatorySystemGestures.asPaddingValues().calculateBottomPadding() + 12.dp)).background(Paper))
    }
}

@Composable private fun SidePane(side: Side, chat: Conversation, active: ProbeRequest?, actions: TalkActions, modifier: Modifier) {
    val chinese = side == Side.CHINESE
    val visible = chat.currentTurns
    Box(modifier.background(if (chinese) Paper else Color(0xFFFBF1E9))) {
        if (visible.isEmpty()) Arches(if (chinese) Line else Color(0xFFE5CCBB),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.37f))
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 3.dp)) {
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
            actions.model?.let { ModelStatus(it, active != null, active?.modelOptions?.utteranceModel) }
            HoldButton(side, active, actions, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

@Composable internal fun HoldButton(side: Side, active: ProbeRequest?, actions: TalkActions,
    modifier: Modifier, compact: Boolean = false) {
    val latest by rememberUpdatedState(actions)
    val current by rememberUpdatedState(active)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val held = active?.side == side
    val sampling = active?.sampling?.collectAsState()?.value == true
    val chinese = side == Side.CHINESE
    var cancelling by remember(side) { mutableStateOf(false) }
    val bg = if (chinese) Forest else Peach
    val fg = if (chinese) Paper else Clay
    val hint = when {
        held && cancelling -> if (chinese) "松开取消" else "Relâcher pour annuler"
        held && !sampling -> if (chinese) "正在准备…" else "Un instant…"
        held -> if (chinese) "松开发送 · 上滑取消" else "Relâcher pour envoyer"
        active != null -> if (chinese) "对方正在说话" else "À son tour"
        chinese -> "按住说话"
        else -> "Maintenez"
    }
    val cancelRed = Color(0xFFAC3439)
    Box(modifier) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 49.dp else 64.dp).clip(RoundedCornerShape(18.dp))
        .background(if (held && cancelling) cancelRed else if (held && chinese) Color(0xFF28594B) else bg)
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
                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (current != null) return@awaitEachGesture
                val id = latest.start(side) ?: return@awaitEachGesture
                var send = false
                val slide = HoldSlide()
                try {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.find { it.id == down.id } ?: break
                        val red = slide.move(change.position.y.toDp().value)
                        if (red != cancelling) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        cancelling = red
                        if (!change.pressed) { send = !cancelling && !change.isConsumed; change.consume(); break }
                        change.consume()
                    }
                } finally { cancelling = false; latest.end(id, !send) }
            }
        }.padding(horizontal = 13.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 34.dp else 37.dp).background(if (chinese) Leaf else Color(0xFFE9C8B6), CircleShape),
            contentAlignment = Alignment.Center) { Glyph(if (held && sampling) Mark.AUDIO else Mark.MIC, if (chinese) Forest else Clay, 19.dp) }
        Spacer(Modifier.width(9.dp))
        if (compact) {
            Text(side.label, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f)); Text(hint, color = if (held && cancelling) Paper else fg, style = MaterialTheme.typography.bodyMedium)
        } else Column {
            Text(side.label, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(hint, color = if (held && cancelling) Paper else fg, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (held) Surface(Modifier.align(Alignment.TopCenter).offset(y = (-46).dp),
        shape = RoundedCornerShape(15.dp), color = if (cancelling) cancelRed else Forest, shadowElevation = 3.dp) {
        Text(if (chinese) (if (cancelling) "松开取消 · 移回继续" else "↑  上滑到这里取消")
            else (if (cancelling) "Relâchez pour annuler" else "↑  Glissez pour annuler"),
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Paper, style = MaterialTheme.typography.labelLarge)
    }
    }
}

@Composable internal fun Timeline(turns: List<Turn>, viewer: Side?, actions: TalkActions, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(turns.lastOrNull()?.id) { if (state.firstVisibleItemIndex <= 1) state.scrollToItem(0) }
    LazyColumn(modifier.fillMaxWidth(), state = state, reverseLayout = true,
        contentPadding = PaddingValues(vertical = 1.dp)) {
        items(turns.asReversed(), key = { it.id }) { TurnCard(it, viewer, actions) }
    }
}

@Composable internal fun TurnCard(turn: Turn, viewer: Side?, actions: TalkActions, remove: (() -> Unit)? = null) {
    val chinese = turn.side == Side.CHINESE
    val frenchUi = viewer == Side.FRENCH
    val color = if (chinese) Forest else Clay
    var details by remember(turn.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("turn-${turn.id}").clickable(enabled = turn.model != null,
        onClickLabel = "查看模型来源") { details = !details }) {
        val ready = turn.status == TurnStatus.READY || turn.status == TurnStatus.DEMO_READY
        if (ready) {
            val originalFirst = viewer == turn.side
            val first = if (originalFirst) turn.sourceText else turn.translatedText
            val second = if (originalFirst) turn.translatedText else turn.sourceText
            val feedback = actions.playback?.takeIf { it.id == turn.id }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 530.dp
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (wide) {
                        Text(if (chinese) "中›法" else "法›中", Modifier.width(40.dp), color = color, style = MaterialTheme.typography.labelSmall)
                        val primary = if (viewer == null && chinese) turn.sourceText else first
                        val secondary = if (viewer == null && chinese) turn.translatedText else second
                        Text(primary, Modifier.weight(1f).padding(end = 9.dp), color = Ink, style = MaterialTheme.typography.bodyLarge)
                        Text(secondary, Modifier.weight(1f).padding(end = 8.dp), color = Muted, style = MaterialTheme.typography.bodyMedium)
                    } else Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 2.dp)) {
                        Text(first, color = Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(second, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    val replayLabel = if (feedback?.active == true && feedback.manual) (if (frenchUi) "Arrêter" else "停止") else if (frenchUi) "Écouter" else "重播"
                    val editLabel = if (frenchUi) "Corriger" else "纠错"
                    if (wide) {
                        TextButton(onClick = { actions.speak(turn) }) { Text(replayLabel, color = color) }
                        TextButton(onClick = { actions.edit(turn) }) { Text(editLabel, color = Muted) }
                    } else {
                        ToolButton(Mark.SPEAK, replayLabel, { actions.speak(turn) }, color, caption = true)
                        ToolButton(Mark.EDIT, editLabel, { actions.edit(turn) }, Muted, caption = true)
                    }
                    if (remove != null) ToolButton(Mark.DELETE, "删除这句 · ${turn.sourceText}", remove, Muted)
                }
            }
            if (feedback != null) {
                Text(feedback.message + (feedback.model?.let { " · ${dev.polybridge.qwen.ModelCatalog.label(it)}" } ?: ""), color = color, style = MaterialTheme.typography.labelSmall)
                if (!feedback.active && actions.phoneSpeak != null) TextButton(onClick = { actions.phoneSpeak.invoke(turn) }) { Text("用手机语音朗读") }
            }
            if (turn.status == TurnStatus.DEMO_READY) Text("旧版示例", color = Muted, style = MaterialTheme.typography.labelSmall)
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
            if (remove != null) ToolButton(Mark.DELETE, "删除这句 · ${turn.sourceText}", remove, Muted)
            Spacer(Modifier.height(4.dp))
        }
        turn.model?.let { trace ->
            Text("模型 · ${dev.polybridge.qwen.ModelCatalog.label(trace.requestedModel)}" + if (details) "  点按收起" else "  点按查看来源", color = Muted, style = MaterialTheme.typography.labelSmall)
            if (details) Text("请求：${trace.requestedModel}\n实际：${trace.actualModel ?: "服务未返回，不推断"}" +
                (trace.requestId?.let { "\n请求编号：$it" } ?: "") + (trace.sessionId?.let { "\n会话编号：$it" } ?: "") +
                (trace.recoveredBy?.let { "\n异常复核：$it" } ?: ""), color = Muted, style = MaterialTheme.typography.bodySmall)
            if (details) trace.recognition?.let { recognition -> Text("原文识别：${recognition.requestedModel}\n识别实际型号：${recognition.actualModel ?: "服务未返回"}", color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider(color = Line)
    }
}

private fun issueText(turn: Turn, fr: Boolean) = when (turn.issue) {
    TranslationIssue.CREDENTIAL -> if (fr) "Vérifiez la clé et la région." else "请到设置检查千问密钥和地区。"
    TranslationIssue.LIMIT -> if (fr) "Quota atteint. Réessayez plus tard." else "千问调用额度或频率已达上限，稍后再试。"
    TranslationIssue.NETWORK -> if (fr) "Connexion interrompue." else "无法连接千问，请检查网络。"
    TranslationIssue.EMPTY_REPLY -> if (fr) "Aucune parole reconnue." else "没识别到内容，请靠近麦克风再说一次。"
    TranslationIssue.SERVICE -> if (fr) "Service indisponible." else "千问未完成这次请求，请检查服务配置后重试。"
    TranslationIssue.INTERRUPTED -> if (fr) "Session interrompue." else "上次处理被中断。"
    TranslationIssue.UNRELIABLE -> if (fr) "Reconnaissance incertaine. Réécoutez le message." else "这段识别出现异常，请重新播放语音。"
    TranslationIssue.AUDIO_PROCESSING -> if (fr) "Traitement audio local indisponible. Réessayez." else "本机声音处理失败，请重新开始。"
    null -> if (fr) "Prise de son interrompue. Réessayez." else turn.verdict?.label ?: "这句话未完成。"
}
