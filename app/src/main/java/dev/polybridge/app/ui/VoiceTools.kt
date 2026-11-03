package dev.polybridge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.polybridge.app.PolyBridgeApp
import dev.polybridge.app.ProbeRequest
import dev.polybridge.app.audio.AudioFileDecoder
import dev.polybridge.app.audio.TranslatedAudioShare
import dev.polybridge.core.*
import kotlinx.coroutines.*

@Composable internal fun VoiceTools(app: PolyBridgeApp, chat: Conversation, selected: String?, select: (String?) -> Unit,
    actions: TalkActions, active: ProbeRequest?, capture: (PlaybackTarget, Side) -> Unit, cloud: () -> Unit, notice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by app.settings.state.collectAsState()
    var side by rememberSaveable { mutableStateOf(Side.FRENCH) }
    var recent by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selectApp by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf(PlaybackTarget.WECHAT) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { select(uri.toString()); error = "" }
    }
    val name by produceState("已选择语音文件", selected) {
        if (selected != null) value = withContext(Dispatchers.IO) { runCatching {
            context.contentResolver.query(Uri.parse(selected), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.replace('\n', ' ')?.take(100) else null
            }
        }.getOrNull() ?: "已选择语音文件" }
    }
    val ready = chat.turns.asReversed().filter { it.status == TurnStatus.READY }
    val imported = chat.turns.find { it.id == recent }
    val turn = imported ?: ready.firstOrNull()
    LaunchedEffect(active?.id) { if (active?.kind == Kind.MESSAGE) recent = active.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeading("MESSAGES VOCAUX", "语音工具")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().background(Mint, Round).padding(16.dp)) {
                Kicker("01  /  RECEVOIR")
                Text("翻译语音文件", color = Ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                Text(if (selected == null) "选择一段不超过两分钟的语音，也可以从其他应用分享到这里。" else name,
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Side.entries.forEach { lang -> FilterChip(side == lang, { side = lang }, {
                        Text(if (lang == Side.CHINESE) "中文 → 法语" else "Français → 中文")
                    }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !pending, onClick = { open.launch(arrayOf("audio/*")) }) {
                        Text(if (selected == null) "选择语音" else "换一段")
                    }
                    if (selected != null) Button(enabled = !pending, onClick = {
                        if (!config.configured) { cloud(); return@Button }
                        val uri = Uri.parse(selected)
                        pending = true; error = ""
                        scope.launch {
                            try {
                                val wave = withContext(Dispatchers.IO) { AudioFileDecoder(context).read(uri) }
                                recent = app.translator.imported(side, wave); select(null)
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (problem: Exception) {
                                error = if (problem.message == "DURATION") "语音超过两分钟，请先截取较短的一段。"
                                    else "手机无法读取这段音频，请选择 WAV、MP3 或手机可以播放的语音文件。"
                            } finally { pending = false }
                        }
                    }) { Text(if (pending) "读取中…" else "开始翻译") }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (active?.kind == Kind.MESSAGE) {
                    Text("正在采集「${active.target.label}」播放的媒体声音，最长一分钟。", color = Ink)
                    Row {
                        TextButton(onClick = { active.end.compareAndSet(null, End.RELEASED) }) { Text("结束并翻译") }
                        TextButton(onClick = { active.end.compareAndSet(null, End.CANCELLED) }) { Text("取消采集") }
                    }
                } else TextButton(enabled = active == null, onClick = { if (config.configured) selectApp = true else cloud() }) { Text("从应用播放中采集") }
                if (turn?.status == TurnStatus.FAILED && turn.verdict in setOf(Verdict.SILENT, Verdict.NO_DATA, Verdict.SYSTEM_SILENCED))
                    Text("没有捕获到可用的媒体声音，可改用「选择语音」导入文件。", color = Muted)
            }
            Kicker("02  /  RÉPONDRE")
            Row {
                Text("译文与语音回复", Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (ready.isNotEmpty()) Box {
                    TextButton(onClick = { choose = true }) { Text("选择一句") }
                    DropdownMenu(choose, { choose = false }) {
                        ready.take(30).forEach { item -> DropdownMenuItem(text = { Text(item.translatedText.take(44)) },
                            onClick = { recent = item.id; choose = false }) }
                    }
                }
            }
            if (turn == null) Text("说一句、打字或导入语音后，可以在这里复制译文、分享译音。", color = Muted)
            else {
                TurnCard(turn, null, actions)
                if (turn.status == TurnStatus.READY) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PolyBridge", turn.translatedText))
                        notice("译文已复制")
                    }) { Text("复制译文") }
                    Button(enabled = !sharing, onClick = {
                        val clip = app.clips[app.clips.key(turn, config.cloud)]
                        if (clip == null && !config.configured) { cloud(); return@Button }
                        sharing = true
                        scope.launch {
                            try {
                                val share = withContext(Dispatchers.IO) {
                                    val voice = clip ?: requireNotNull(app.settings.provider()).synthesize(turn.side.target, turn.translatedText)
                                    TranslatedAudioShare(context).create(voice)
                                }
                                context.startActivity(Intent.createChooser(share, "分享译音"))
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { notice("译音生成失败，请检查网络后再试") }
                            finally { sharing = false }
                        }
                    }) { Text(if (sharing) "生成中…" else "分享译音") }
                }
            }
            HorizontalDivider(color = Line)
            Kicker("03  /  CLAVIER")
            Text("翻译键盘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("在系统里启用 PolyBridge 后，可在聊天输入框翻译选中文字，或插入最近的译文。", color = Muted,
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("启用键盘") }
                TextButton(onClick = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) { Text("切换键盘") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (selectApp) AlertDialog(onDismissRequest = { selectApp = false }, title = { Text("采集语音消息") }, text = {
        Column {
            Text("选择来源，按系统提示共享该应用，然后播放一条已获准翻译的语音消息。只采集所选应用允许共享的媒体声音。")
            listOf(PlaybackTarget.WECHAT, PlaybackTarget.WHATSAPP, PlaybackTarget.WHATSAPP_BUSINESS).forEach { item ->
                TextButton(onClick = { target = item }) { Text((if (item == target) "✓  " else "") + item.label) }
            }
        }
    }, confirmButton = { Button(onClick = { selectApp = false; capture(target, side) }) { Text("申请系统共享") } },
        dismissButton = { TextButton(onClick = { selectApp = false }) { Text("取消") } })
}
