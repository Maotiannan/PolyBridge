package dev.polybridge.app

import android.content.Context
import android.media.projection.MediaProjection
import dev.polybridge.app.audio.*
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
    @Test fun screenCaptionsIgnoreDraftsPersistOriginalAndDrainFinalBeforeStopping() {
        run(Kind.CAPTIONS, interrupted = false)
    }
    @Test fun interruptionPreservesOriginalAsFailedInsteadOfSpinningAfterRestart() {
        run(Kind.CAPTIONS, interrupted = true)
    }
    @Test fun microphoneModeKeepsItsExistingLivePreview() {
        run(Kind.LISTEN, interrupted = false)
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
                    if (kind == Kind.CAPTIONS) assertTrue(desk.view.value.drafts.isEmpty())
                    else assertEquals("Bon", desk.view.value.partial?.source)
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
        assertEquals(if (interrupted) "" else "你好。", line.translatedText)
        assertTrue(desk.view.value.drafts.isEmpty())
        assertNull(desk.view.value.id)
    }
}
