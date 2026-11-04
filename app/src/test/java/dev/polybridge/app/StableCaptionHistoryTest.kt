package dev.polybridge.app

import android.content.Context
import dev.polybridge.app.audio.ListeningState
import dev.polybridge.app.ui.transcriptRows
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StableCaptionHistoryTest {
    @Test fun durableOriginalAndLateTranslationsKeepAllOneHundredFiftySentencesInSpokenOrder() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = ProbeStore(context)
        val origins = { time: Long -> LiveOrigin("offline-caption-fixture", Kind.CAPTIONS, time) }
        val receive: (LiveUpdate) -> Unit = { event -> when (event) {
            is LiveUpdate.Caption -> store.changeChat(true) { it.heardSource(event, origins(event.startedMs)) }
            is LiveUpdate.Finished -> store.changeChat(true) { it.heard(LiveUpdate.Caption(event.id, event.side,
                event.source, event.target, event.startedMs), origins(event.startedMs), event.issue) }
            else -> Unit
        } }
        repeat(150) { i ->
            val start = i * 20_000L // Long pauses do not discard any preceding sentence.
            receive(LiveUpdate.Caption("line-$i", Side.FRENCH, "Provisoire", "半句", start))
            receive(LiveUpdate.Caption("line-$i", Side.FRENCH, "Phrase $i.", "", start, sourceFinal = true))
            val disk = ProbeStore(context).chat.value.turns.last()
            assertEquals("Phrase $i.", disk.sourceText)
            assertEquals("", disk.translatedText)
            assertEquals(TurnStatus.FAILED, disk.status) // Simulated restart preserves, but does not keep spinning.
            if (i % 2 == 1) for (j in listOf(i, i - 1)) {
                receive(LiveUpdate.Finished("line-$j", Side.FRENCH, j * 20_000L,
                    "Phrase $j.", "第 $j 句。", byteArrayOf()))
            }
        }
        val reopened = ProbeStore(context).chat.value
        val rows = transcriptRows(reopened, ListeningState(), Kind.CAPTIONS, null)
        assertEquals(150, rows.size)
        rows.forEachIndexed { i, row ->
            assertEquals("Phrase $i.", row.line.source)
            assertEquals("第 $i 句。", row.line.target)
            assertEquals(TurnStatus.READY, row.saved?.status)
        }
    }
}
