package dev.polybridge.app.ui

import android.content.Intent
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
import dev.polybridge.app.*
import dev.polybridge.app.audio.TranslatedAudioShare
import dev.polybridge.core.*
import kotlinx.coroutines.*

@Composable internal fun CallAssistPanel(app: PolyBridgeApp, chat: Conversation, active: ProbeRequest?, actions: TalkActions,
    playFrench: (Turn) -> Unit, captions: () -> Unit, notice: (String) -> Unit) {
    var recent by rememberSaveable { mutableStateOf<String?>(null) }
    val turn = chat.turns.lastOrNull { it.id == recent }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    val controls = TalkActions({ actions.start(Side.CHINESE)?.also { recent = it } }, actions.end, actions.speak, actions.edit, actions.type, actions.playback, actions.phoneSpeak, actions.model)
    Column(Modifier.fillMaxSize()) {
        PageHeading("PARLER FRANÇAIS", "中文转法语")
        actions.model?.let { ModelStatus(it, active != null, active?.modelOptions?.utteranceModel) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().background(Mint, Round).padding(18.dp)) {
                Kicker("我说中文  →  对方看法语")
                Text("按住说中文，松手出法语", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("可以在本机播放，也可以把这句法语作为语音文件分享给对方。", color = Muted)
            }
            if (turn != null) {
                TurnCard(turn, null, controls)
                if (turn.status == TurnStatus.READY) {
                    Button(enabled = active == null, onClick = { playFrench(turn) }) { Text("在本机播放法语") }
                    OutlinedButton(enabled = active == null && !sharing, onClick = {
                        val options = runCatching { app.settings.state.value.optionsFor(turn) }.getOrElse {
                            notice("请先检查模型设置"); return@OutlinedButton
                        }
                        sharing = true
                        scope.launch {
                            try {
                                val share = withContext(Dispatchers.IO) {
                                    val key = app.clips.key(turn, options)
                                    val wave = app.clips.find(turn, options)?.wave ?: requireNotNull(app.settings.provider(options))
                                        .synthesize(turn.side.target, turn.translatedText).also { app.clips.put(key, it) }
                                    TranslatedAudioShare(context).create(wave)
                                }
                                context.startActivity(Intent.createChooser(share, "分享法语语音"))
                            } catch (e: CancellationException) { throw e }
                              catch (_: Exception) { notice("这句语音暂时无法分享，请重试") }
                            finally { sharing = false }
                        }
                    }) { Text(if (sharing) "正在准备…" else "分享法语语音") }
                }
            }
            HorizontalDivider(color = Line)
            Text("正在打电话？", color = Ink, fontWeight = FontWeight.Bold)
            Text("普通电话、微信和 WhatsApp 通话目前不能直接把这段法语送进对方的听筒。听聊天里的语音消息，可以开启屏幕字幕。", color = Muted)
            TextButton(onClick = captions) { Text("去看屏幕字幕") }
        }
        HoldButton(Side.CHINESE, active, controls, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp))
    }
}
