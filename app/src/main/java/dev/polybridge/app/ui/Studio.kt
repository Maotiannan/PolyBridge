package dev.polybridge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.polybridge.app.*
import dev.polybridge.app.audio.ConversationVoice
import dev.polybridge.app.audio.Hardware
import dev.polybridge.core.*

@Composable internal fun Studio(app: PolyBridgeApp, lifecycle: Lifecycle, chat: Conversation, active: ProbeRequest?,
    hardware: Hardware, requestBluetooth: () -> Unit, installedVersion: (PlaybackTarget) -> String?,
    incomingShare: String?, consumeShare: () -> Unit,
    start: (Side) -> String?, listen: () -> String?, captureMessage: (PlaybackTarget, Side) -> Unit,
    end: (String, Boolean) -> Unit, notice: (String) -> Unit,
    inputLabel: String, inputPicker: @Composable (() -> Unit) -> Unit, diagnostics: @Composable () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.TALK) }
    var showCloud by rememberSaveable { mutableStateOf(false) }
    var showInput by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf<Side?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFile by rememberSaveable { mutableStateOf<String?>(null) }
    val config by app.settings.state.collectAsState()
    val context = LocalContext.current
    val voice = remember(context) { ConversationVoice(context.applicationContext, app.settings, app.clips, notice) }
    val playback by voice.state.collectAsState()
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.close() }
    }
    fun replay(turn: Turn) {
        if (app.store.active.value != null) { notice("收音结束后可重播"); return }
        if (playback?.id == turn.id && playback?.active == true && playback?.manual == true) voice.stop()
        else voice.translation(turn, manual = true)
    }
    val autoPlay by rememberUpdatedState<(Turn) -> Unit> { turn ->
        if (config.autoSpeak && page == Page.TALK && editing == null && app.store.chat.value.currentTurns.lastOrNull() == turn &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && app.store.active.value == null &&
            voice.state.value?.let { it.manual && it.active } != true)
            voice.translation(turn, manual = false)
    }
    LaunchedEffect(app.translator) { app.translator.newTranslations.collect { autoPlay(it) } }
    fun navigate(next: Page) {
        if (next != page) {
            voice.stop()
            active?.takeIf { it.kind == Kind.HOLD || it.kind == Kind.LISTEN }?.let {
                end(it.id, true)
                if (it.kind == Kind.LISTEN) app.live.stopNow(it)
            }
            page = next
        }
    }
    val back = { navigate(if (page == Page.DIAGNOSTICS) Page.SETTINGS else Page.TALK) }
    LaunchedEffect(incomingShare) { if (incomingShare != null) { selectedFile = incomingShare; navigate(Page.TOOLS); consumeShare() } }
    BackHandler(page != Page.TALK, onBack = back)
    val actions = TalkActions(start = {
        if (!config.configured) { showCloud = true; null } else { voice.stop(); start(it) }
    }, end = end, speak = ::replay, edit = { voice.stop(); editing = it.id },
        type = { if (config.configured) typing = it else showCloud = true }, playback = playback)
    Surface(color = Paper) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).semantics { testTagsAsResourceId = true }) {
            val foldedOpen = maxWidth >= 600.dp && maxHeight >= 430.dp
            if (foldedOpen && page == Page.TALK) FoldTalk(chat, active, config.opposed, actions,
                flip = { app.settings.orientation(!config.opposed) }, navigate = ::navigate)
            else Column(Modifier.fillMaxSize()) {
                if (page == Page.SETTINGS || page == Page.DIAGNOSTICS) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolButton(Mark.BACK, "返回", back)
                        Text(if (page == Page.DIAGNOSTICS) "设备诊断" else "PolyBridge", color = Muted, style = MaterialTheme.typography.titleMedium)
                    }
                } else Masthead(config.configured, { navigate(Page.SETTINGS) }, { showCloud = true })
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (page) {
                        Page.TALK -> CompactTalk(chat, active, actions, { navigate(Page.BOOK) }, { navigate(Page.LIVE) }, { navigate(Page.CALL) })
                        Page.CALL -> CallAssistPanel(app, chat, installedVersion, { showCloud = true }, { navigate(Page.TOOLS) }, notice)
                        Page.LIVE -> ListeningPanel(app, chat, active, actions, start = {
                            if (!config.configured) showCloud = true else { voice.stop(); listen() }
                        }, stop = { active?.let { end(it.id, false) } })
                        Page.BOOK -> Phrasebook { if (app.store.active.value == null) voice.phrase(it) else notice("收音结束后可播放词本") }
                        Page.TOOLS -> VoiceTools(app, chat, selectedFile, { selectedFile = it }, actions, active, captureMessage, { showCloud = true }, notice)
                        Page.HISTORY -> HistoryPanel(chat, actions, active == null, fresh = {
                            app.store.changeChat { it.newSession() }; navigate(Page.TALK)
                        }, remove = { id -> voice.stop(); app.store.changeChat(durable = true) { it.copy(turns = it.turns.filterNot { turn -> turn.id == id }) } },
                            clear = { voice.stop(); app.store.clearHistory()?.also { app.clips.clear() } ?: run { notice("暂时无法清空，请稍后重试"); null } },
                            undo = { previous -> app.store.changeChat(durable = true) { it.restoreHistory(previous) } })
                        Page.SETTINGS -> SettingsPanel(app.settings, { showCloud = true }, { navigate(Page.DIAGNOSTICS) },
                            inputLabel, { showInput = true }, hardware, requestBluetooth)
                        Page.DIAGNOSTICS -> Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) { diagnostics() }
                    }
                }
                if (page in listOf(Page.TALK, Page.BOOK, Page.HISTORY, Page.LIVE, Page.TOOLS, Page.CALL)) Navigation(page, ::navigate)
            }
        }
    }
    if (showCloud) CloudDialog(app.settings, { showCloud = false }) { showCloud = false; notice("千问设置已保存") }
    if (showInput) inputPicker { showInput = false }
    typing?.let { initial ->
        TextDialog(null, initial, { typing = null }, retry = null, submit = { side, source, _ ->
            app.translator.typed(side, source); typing = null
        })
    }
    chat.turns.find { it.id == editing }?.let { turn ->
        TextDialog(turn, turn.side, { editing = null }, retry = { source ->
            if (config.configured) { app.translator.retry(turn.id, source); editing = null } else showCloud = true
        }, submit = { _, source, target ->
            val same = source == turn.sourceText && target == turn.translatedText && turn.status == TurnStatus.READY
            if (same || app.store.changeChat(durable = true) { it.correct(turn.id, source, target) }) {
                notice("已保存修改"); editing = null
            } else notice("修改未保存，请重试")
        })
    }
}

@Composable private fun HistoryPanel(chat: Conversation, actions: TalkActions, idle: Boolean, fresh: () -> Unit, remove: (String) -> Unit,
    clear: () -> Conversation?, undo: (Conversation) -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf<Conversation?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(chat.turns.lastOrNull()?.id) { if (list.firstVisibleItemIndex <= 1 && chat.turns.isNotEmpty()) list.scrollToItem(0) }
    LaunchedEffect(previous) { if (previous != null) { kotlinx.coroutines.delay(6000); previous = null } }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("对话历史", color = Ink, style = MaterialTheme.typography.titleLarge)
                Text("${chat.turns.size} / 100 句 · 本机", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = fresh, enabled = idle) { Text("新对话") }
            if (previous != null) TextButton(onClick = { previous?.let(undo); previous = null }) { Text("撤销清空") }
            else TextButton(onClick = { previous = clear() }, enabled = idle && chat.turns.isNotEmpty()) { Text("清空历史") }
        }
        HorizontalDivider(color = Line)
        if (chat.turns.isEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Glyph(Mark.HISTORY, Forest, 32.dp); Spacer(Modifier.height(12.dp))
            Text("还没有对话", color = Ink, style = MaterialTheme.typography.titleLarge)
            Text("说过的话和译文会按时间出现在这里。", color = Muted)
        } else LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp)) {
            items(chat.turns.asReversed(), key = { it.id }) { turn ->
                TurnCard(turn, null, actions, if (turn.status in setOf(TurnStatus.READY, TurnStatus.DEMO_READY, TurnStatus.FAILED)) {
                    { deleting = turn.id }
                } else null)
            }
        }
    }
    chat.turns.find { it.id == deleting }?.let { turn ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这句对话？") },
            text = { Text(turn.sourceText.ifBlank { "这条记录将从本机移除。" }) },
            confirmButton = { TextButton(onClick = { remove(turn.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}

@Composable private fun TextDialog(turn: Turn?, initial: Side, dismiss: () -> Unit, retry: ((String) -> Unit)?, submit: (Side, String, String) -> Unit) {
    var side by rememberSaveable { mutableStateOf(initial) }
    var source by rememberSaveable { mutableStateOf(turn?.sourceText.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(turn?.translatedText.orEmpty()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (turn == null) "打字翻译" else "校对这句话") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (turn == null) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Side.entries.forEach { lang -> FilterChip(side == lang, { side = lang }, {
                    Text(if (lang == Side.CHINESE) "中文 → 法语" else "Français → 中文")
                }) }
            }
            OutlinedTextField(source, { source = it.take(4000) }, label = { Text("原文 · ${side.label}") }, minLines = 2, maxLines = 5)
            if (turn != null) OutlinedTextField(target, { target = it.take(4000) }, label = { Text("译文") }, minLines = 2, maxLines = 5)
            if (retry != null) TextButton(onClick = { retry(source.trim()) }, enabled = source.isNotBlank()) { Text("用原文重新翻译") }
        }
    }, confirmButton = { Button(onClick = { submit(side, source.trim(), target.trim()) },
        enabled = source.isNotBlank() && (turn == null || target.isNotBlank())) { Text(if (turn == null) "翻译" else "保存修改") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
