package dev.polybridge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.polybridge.app.audio.ListeningState
import dev.polybridge.core.*

internal data class TranscriptRow(val line: LiveUpdate.Caption, val saved: Turn?)

internal fun transcriptRows(chat: Conversation, state: ListeningState, kind: Kind, previousId: String?): List<TranscriptRow> {
    val current = state.takeIf { it.kind == kind }
    val capture = current?.captureId ?: chat.turns.lastOrNull { it.live?.kind == kind }?.live?.captureId ?: previousId
        ?: return emptyList()
    val prefix = "live-$capture-"
    val saved = chat.turns.filter { it.live?.captureId == capture || it.live == null && it.id.startsWith(prefix) }
    val confirmed = saved.map { turn -> TranscriptRow(LiveUpdate.Caption(turn.id, turn.side,
        turn.sourceText, turn.translatedText, turn.live?.offsetMs ?: 0, turn.issue), turn) }
    val drafts = current?.drafts.orEmpty()
    if (drafts.isEmpty()) return confirmed
    val pending = drafts.filter { line -> saved.none { it.id == line.id } }.map { TranscriptRow(it, null) }
    return (confirmed + pending).sortedBy { it.line.startedMs }
}

@Composable internal fun TranscriptList(rows: List<TranscriptRow>, modifier: Modifier, actions: TalkActions? = null) {
    val scroll = rememberLazyListState()
    LaunchedEffect(rows.lastOrNull()?.line?.id) {
        if (rows.isNotEmpty() && scroll.firstVisibleItemIndex <= 1) scroll.scrollToItem(0)
    }
    LazyColumn(modifier.fillMaxWidth(), state = scroll, reverseLayout = true) {
        items(rows.asReversed(), key = { it.line.id }) { row ->
            if (row.saved?.status == TurnStatus.READY && actions != null) TurnCard(row.saved, null, actions)
            else Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                val direction = if (row.line.side == Side.FRENCH) "法语 → 中文" else "中文 → 法语"
                Kicker(direction + when { row.line.issue == TranslationIssue.UNRELIABLE -> "  ·  识别待核对";
                    row.saved?.status == TurnStatus.TRANSLATING -> "  ·  原文已保存，正在翻译";
                    row.saved == null -> "  ·  正在听"; row.saved.status == TurnStatus.FAILED -> "  ·  译文未完成"; else -> "" })
                Text(row.line.source, color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (row.line.issue == TranslationIssue.UNRELIABLE) Text(
                    if (row.saved == null) "正在重新核对原音…" else "未能确认这段识别，请重新播放语音。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (row.line.target.isNotBlank()) Text(row.line.target, color = Ink, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider(Modifier.padding(top = 5.dp), color = Line)
            }
        }
    }
}
