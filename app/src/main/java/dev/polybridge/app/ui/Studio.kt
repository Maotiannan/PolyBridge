package dev.polybridge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.polybridge.app.*
import dev.polybridge.app.audio.ConversationVoice
import dev.polybridge.core.*

@Composable internal fun Studio(app: PolyBridgeApp, lifecycle: Lifecycle, chat: Conversation, active: ProbeRequest?,
    start: (Side) -> String?, end: (String, Boolean) -> Unit, notice: (String) -> Unit,
    inputLabel: String, inputPicker: @Composable (() -> Unit) -> Unit, diagnostics: @Composable () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.TALK) }
    var showCloud by rememberSaveable { mutableStateOf(false) }
    var showInput by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf<Side?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val config by app.settings.state.collectAsState()
    val context = LocalContext.current
    val voice = remember(context) { ConversationVoice(context.applicationContext, app.settings::provider, notice) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.close() }
    }
    fun speak(language: String, text: String) {
        if (app.store.active.value != null) { notice("收音结束后可重播"); return }
        voice.translation(language, text)
    }
    val autoPlay by rememberUpdatedState<(Turn) -> Unit> { turn ->
        if (config.autoSpeak && page == Page.TALK && turn.session == app.store.chat.value.session &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && active == null)
            speak(turn.side.target, turn.translatedText)
    }
    LaunchedEffect(app.translator) { app.translator.newTranslations.collect { autoPlay(it) } }
    fun navigate(next: Page) {
        if (next != page) { voice.stop(); active?.takeIf { it.kind == Kind.HOLD }?.let { end(it.id, true) }; page = next }
    }
    val back = { navigate(if (page == Page.DIAGNOSTICS) Page.SETTINGS else Page.TALK) }
    BackHandler(page != Page.TALK, onBack = back)
    val actions = TalkActions(start = {
        if (!config.configured) { showCloud = true; null } else { voice.stop(); start(it) }
    }, end = end, speak = { speak(it.side.target, it.translatedText) }, edit = { editing = it.id },
        type = { if (config.configured) typing = it else showCloud = true })
    Surface(color = Paper) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                        Page.TALK -> CompactTalk(chat, active, actions) { navigate(Page.BOOK) }
                        Page.BOOK -> Phrasebook { if (app.store.active.value == null) voice.phrase(it) else notice("收音结束后可播放词本") }
                        Page.HISTORY -> HistoryPanel(chat, actions, active == null, fresh = {
                            app.store.changeChat { it.newSession() }; navigate(Page.TALK)
                        }, remove = { id -> app.store.changeChat { it.copy(turns = it.turns.filterNot { turn -> turn.id == id }) } })
                        Page.SETTINGS -> SettingsPanel(app.settings, { showCloud = true }, { navigate(Page.DIAGNOSTICS) }, inputLabel, { showInput = true })
                        Page.DIAGNOSTICS -> Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) { diagnostics() }
                    }
                }
                if (page in listOf(Page.TALK, Page.BOOK, Page.HISTORY)) Navigation(page, ::navigate)
            }
        }
    }
    if (showCloud) CloudDialog(app.settings, { showCloud = false }) { showCloud = false; notice("千问设置已保存") }
    if (showInput) inputPicker { showInput = false }
    typing?.let { initial ->
        TextDialog(null, initial, { typing = null }, { side, source, _ ->
            app.translator.typed(side, source); typing = null
        })
    }
    chat.turns.find { it.id == editing }?.let { turn ->
        TextDialog(turn, turn.side, { editing = null }, { _, source, target ->
            if (turn.status == TurnStatus.FAILED || source != turn.sourceText) {
                if (config.configured) app.translator.retry(turn.id, source) else showCloud = true
            } else app.store.changeChat { it.editTranslation(turn.id, target) }
            editing = null
        })
    }
}

@Composable private fun HistoryPanel(chat: Conversation, actions: TalkActions, idle: Boolean, fresh: () -> Unit, remove: (String) -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        PageHeading("ARCHIVES", "对话历史", "最近 100 句保存在这台手机")
        OutlinedButton(onClick = fresh, enabled = idle, modifier = Modifier.padding(horizontal = 20.dp)) { Text("开始新对话") }
        if (chat.turns.isEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Glyph(Mark.HISTORY, Forest, 32.dp); Spacer(Modifier.height(12.dp))
            Text("还没有对话", color = Ink, style = MaterialTheme.typography.titleLarge)
            Text("说过的话和译文会按时间出现在这里。", color = Muted)
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable private fun TextDialog(turn: Turn?, initial: Side, dismiss: () -> Unit, submit: (Side, String, String) -> Unit) {
    var side by rememberSaveable { mutableStateOf(initial) }
    var source by rememberSaveable { mutableStateOf(turn?.sourceText.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(turn?.translatedText.orEmpty()) }
    val translate = turn == null || turn.status == TurnStatus.FAILED || source.trim() != turn.sourceText
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (turn == null) "打字翻译" else "校对这句话") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (turn == null) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Side.entries.forEach { lang -> FilterChip(side == lang, { side = lang }, {
                    Text(if (lang == Side.CHINESE) "中文 → 法语" else "Français → 中文")
                }) }
            }
            OutlinedTextField(source, { source = it.take(4000) }, label = { Text("原文 · ${side.label}") }, minLines = 2, maxLines = 5)
            if (turn != null && !translate) OutlinedTextField(target, { target = it.take(4000) }, label = { Text("译文") }, minLines = 2, maxLines = 5)
            if (turn != null && translate) Text("保存后按修改的原文重新翻译。", color = Muted)
        }
    }, confirmButton = { Button(onClick = { submit(side, source.trim(), target.trim()) },
        enabled = source.isNotBlank() && (translate || target.isNotBlank())) { Text(if (translate) "翻译" else "保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
