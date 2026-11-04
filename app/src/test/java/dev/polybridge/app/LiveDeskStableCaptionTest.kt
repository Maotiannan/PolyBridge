package dev.polybridge.app

import android.content.Context
import android.media.projection.MediaProjection
import dev.polybridge.app.audio.*
import dev.polybridge.app.ui.CaptionContent
import dev.polybridge.app.ui.transcriptRows
import android.view.ViewGroup
import android.widget.TextView
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the shipping record()/receive()/finally path; Android ONNX and media capture are separate device checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveDeskStableCaptionTest {
    @Test fun screenCaptionsPreviewImmediatelyPersistOriginalAndDrainFinalBeforeStopping() {
        run(Kind.CAPTIONS, interrupted = false)
    }
    @Test fun interruptionPreservesOriginalAsFailedInsteadOfSpinningAfterRestart() {
        run(Kind.CAPTIONS, interrupted = true)
    }
    @Test fun microphoneModeKeepsItsExistingLivePreview() {
        run(Kind.LISTEN, interrupted = false)
    }

    @Test fun twentyEightSecondCaptionGrowsBeforeInputEndsAndAfterOriginalIsSaved() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = ProbeStore(context, "stream-28-seconds")
        var receiver: ((LiveUpdate) -> Unit)? = null
        var receivedMs = 0
        var finished = false
        val overlay = CaptionContent(context) {}
        fun texts(view: android.view.View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        lateinit var desk: LiveDesk
        desk = LiveDesk(context, store, TranslationSettings(context), VoiceClips(),
            captionConnection = { emit ->
                receiver = emit
                object : LiveSpeechSession {
                    override fun connect() = Unit
                    override fun send(samples: ShortArray, length: Int, rate: Int) { receivedMs += length * 1000 / rate }
                    override fun finish() { finished = true; emit(LiveUpdate.Finished("a", Side.FRENCH, 0,
                        "Je voudrais deux billets pour demain.", "我想买两张明天的票。", byteArrayOf())) }
                    override fun close() = Unit
                }
            }, filter = { transport -> object : ListeningFilter, LiveSpeechSession by transport {
                override fun recheckOpening(sendOpening: () -> Unit): Boolean { sendOpening(); return false }
            } }, microphone = { input -> object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted()
                    val emit = requireNotNull(receiver)
                    val frame = ShortArray(320)
                    for (second in 1..28) {
                        repeat(50) { input(frame, frame.size, 16000) }
                        if (second == 4 || second == 8 || second == 16 || second == 27) {
                            val source = if (second < 16) "Je voudrais" else "Je voudrais deux billets pour demain."
                            val target = when (second) {
                                4 -> "我想"
                                8 -> "我想买"
                                16 -> "我想买两张"
                                else -> "我想买两张明天的票"
                            }
                            emit(LiveUpdate.Caption("a", Side.FRENCH, source, target, 0, sourceFinal = second >= 16))
                            assertNull("input is still recording", request.end.get())
                            assertFalse("no finish event yet", finished)
                            val rows = transcriptRows(store.chat.value, desk.view.value, Kind.CAPTIONS, null)
                            assertEquals("preview at $second s must not wait until 28 s", target, rows.singleOrNull()?.line?.target)
                            overlay.render(rows)
                            assertTrue("overlay must show the translation, not a waiting label at $second s", target in texts(overlay))
                        }
                    }
                    request.end.set(End.RELEASED)
                    return request.empty(Verdict.SIGNAL)
                }
            } })
        val request = requireNotNull(store.begin(Kind.CAPTIONS, PlaybackTarget.SELF, null, null, null, "qa.player"))
        assertEquals(Verdict.SIGNAL, desk.record(request).verdict)
        assertEquals(28_000, receivedMs)
        assertTrue(finished)
        assertEquals("我想买两张明天的票。", store.chat.value.turns.single().translatedText)
        val saved = store.chat.value.turns.single()
        val stale = LiveUpdate.Caption(saved.id, saved.side, saved.sourceText, "迟到的半句", 0)
        val rows = transcriptRows(store.chat.value, desk.view.value.copy(drafts = listOf(stale)), Kind.CAPTIONS, null)
        assertEquals("a stale preview cannot overwrite a finished translation", saved.translatedText, rows.single().line.target)
        requireNotNull(receiver)(stale.copy(id = "a"))
        assertEquals(saved, store.chat.value.turns.single())
    }

    private fun run(kind: Kind, interrupted: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = "desk-$kind-$interrupted"
        val store = ProbeStore(context, prefs)
        var receiver: ((LiveUpdate) -> Unit)? = null
        val link: ((LiveUpdate) -> Unit) -> LiveSpeechSession = { callback ->
            receiver = callback
            object : LiveSpeechSession {
                override fun connect() = Unit
                override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
                override fun finish() { callback(LiveUpdate.Finished("a", Side.FRENCH, 30, "Bonjour.", "你好。", byteArrayOf())) }
                override fun close() = Unit
            }
        }
        lateinit var desk: LiveDesk
        desk = LiveDesk(context, store, TranslationSettings(context), VoiceClips(), connect = link, captionConnection = link,
            filter = { transport -> object : ListeningFilter, LiveSpeechSession by transport {
                override fun recheckOpening(sendOpening: () -> Unit): Boolean { sendOpening(); return false }
            } }, microphone = { _ -> object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted()
                    val emit = requireNotNull(receiver)
                    emit(LiveUpdate.Caption("a", Side.FRENCH, "Bon", "你", 30))
                    assertEquals("Bon", desk.view.value.partial?.source)
                    assertEquals("你", desk.view.value.partial?.target)
                    emit(LiveUpdate.Caption("a", Side.FRENCH, "Bonjour.", "你好", 30, sourceFinal = true))
                    if (kind == Kind.CAPTIONS) {
                        assertEquals("Bonjour.", store.chat.value.turns.single().sourceText)
                        assertEquals("", store.chat.value.turns.single().translatedText)
                        assertEquals(TurnStatus.TRANSLATING, store.chat.value.turns.single().status)
                        assertEquals("Bonjour.", ProbeStore(context, prefs).chat.value.turns.single().sourceText)
                    }
                    request.end.set(if (interrupted) End.INTERRUPTED else End.RELEASED)
                    return request.empty(Verdict.SIGNAL)
                }
            } })
        val request = requireNotNull(store.begin(kind, PlaybackTarget.SELF, null, null, null, "qa.player"))
        val report = desk.record(request)
        assertEquals(Verdict.SIGNAL, report.verdict)
        val line = ProbeStore(context, prefs).chat.value.turns.single()
        assertEquals("Bonjour.", line.sourceText)
        assertEquals(if (interrupted) TurnStatus.FAILED else TurnStatus.READY, line.status)
        assertEquals(if (interrupted) "你好" else "你好。", line.translatedText)
        assertTrue(desk.view.value.drafts.isEmpty())
        assertNull(desk.view.value.id)
    }
}
