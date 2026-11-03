package dev.polybridge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import dev.polybridge.core.*

@Composable internal fun CallAssistPanel(app: PolyBridgeApp, chat: Conversation, version: (PlaybackTarget) -> String?,
    cloud: () -> Unit, tools: () -> Unit, notice: (String) -> Unit) {
    val context = LocalContext.current
    val service by app.settings.state.collectAsState()
    var target by rememberSaveable { mutableStateOf(PlaybackTarget.WECHAT) }
    var menu by remember { mutableStateOf(false) }
    var side by rememberSaveable { mutableStateOf(Side.CHINESE) }
    var draft by rememberSaveable { mutableStateOf("") }
    var resultId by rememberSaveable { mutableStateOf<String?>(null) }
    val turn = chat.turns.firstOrNull { it.id == resultId }
    val sending = turn?.status in setOf(TurnStatus.TRANSCRIBING, TurnStatus.TRANSLATING)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeading("PENDANT L’APPEL", "通话辅助")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(target.label + "  ▾") }
                DropdownMenu(menu, { menu = false }) {
                    PlaybackTarget.entries.filter { it.packageName != null }.forEach {
                        DropdownMenuItem({ Text(it.label) }, onClick = { target = it; menu = false })
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Mint, Round).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Kicker(version(target)?.let { "本机版本  $it" } ?: "本机未找到此应用")
                Text("听到对方的通话声音", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("待双方通话实测。", color = Muted)
                Text("让对方听到合成译音", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("待对端设备确认。当前可以发文字或分享译音文件。", color = Muted)
            }
            Kicker("RÉPONSE ÉCRITE")
            Text("用文字回复", color = Ink, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Side.entries.forEach { lang -> FilterChip(side == lang, { side = lang }, {
                    Text(if (lang == Side.CHINESE) "中文 → 法语" else "Français → 中文")
                }) }
            }
            OutlinedTextField(draft, { draft = it.take(1500) }, Modifier.fillMaxWidth(),
                label = { Text("想告诉对方什么？") }, maxLines = 5)
            Button(enabled = draft.isNotBlank() && !sending, onClick = {
                if (service.configured) resultId = app.translator.typed(side, draft) else cloud()
            }) { Text(if (sending) "翻译中…" else "翻译这句话") }
            if (turn?.status == TurnStatus.READY) {
                Text(turn.translatedText, color = Ink, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PolyBridge", turn.translatedText))
                    notice("已复制，可粘贴到" + target.label)
                }) { Text("复制译文") }
            } else if (turn?.status == TurnStatus.FAILED) Text("翻译未完成，可检查连接后重试。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = tools) { Text("分享译音或使用翻译键盘") }
            Spacer(Modifier.height(8.dp))
        }
    }
}
