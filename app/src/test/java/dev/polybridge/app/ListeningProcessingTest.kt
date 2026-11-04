package dev.polybridge.app

import android.content.Context
import android.media.projection.MediaProjection
import dev.polybridge.app.audio.*
import dev.polybridge.audio.AudioProcessingFailure
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListeningProcessingTest {
    @Test fun preferencePersistsAndIsReadAgainAtTheNextSessionNotMidCapture() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        assertTrue(settings.state.value.listeningDenoise)
        settings.listeningDenoise(false)
        assertFalse(TranslationSettings(context).state.value.listeningDenoise)
        val store = ProbeStore(context, "denoise-toggle")
        val choices = mutableListOf<Boolean>()
        val desk = LiveDesk(context, store, settings, VoiceClips(), connect = { Transport() },
            filter = { link, enabled -> choices += enabled; object : ListeningFilter, LiveSpeechSession by link {
                override fun recheckOpening(sendOpening: () -> Unit): Boolean { sendOpening(); return false }
            } }, microphone = { _ -> object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted(); settings.listeningDenoise(true)
                    request.end.set(End.RELEASED); return request.empty(Verdict.SIGNAL)
                }
            } })
        repeat(2) {
            val request = requireNotNull(store.begin(Kind.LISTEN, PlaybackTarget.SELF, null, null, null))
            val report = desk.record(request)
            assertEquals(Verdict.SIGNAL, report.verdict)
            store.finish(report)
        }
        assertEquals(listOf(false, true), choices)
        assertTrue(TranslationSettings(context).state.value.listeningDenoise)
    }

    @Test fun localFailureDuringInputStopsCaptureAndClosesTheFilterWithoutFlushing() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = ProbeStore(context, "denoise-input-failure")
        val transport = Transport()
        val desk = LiveDesk(context, store, TranslationSettings(context), VoiceClips(), connect = { transport },
            filter = { link, _ -> object : ListeningFilter, LiveSpeechSession by link {
                override fun recheckOpening(sendOpening: () -> Unit): Boolean { sendOpening(); return false }
                override fun send(samples: ShortArray, length: Int, rate: Int) { throw AudioProcessingFailure(IllegalStateException()) }
            } }, microphone = { push -> object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted(); push(ShortArray(320), 320, 16000)
                    error("must not keep reading input")
                }
            } })
        val request = requireNotNull(store.begin(Kind.LISTEN, PlaybackTarget.SELF, null, null, null))
        assertEquals(Verdict.FAILED, desk.record(request).verdict)
        assertEquals(TranslationIssue.AUDIO_PROCESSING, desk.view.value.issue)
        assertEquals(0, transport.finishes); assertEquals(1, transport.closes)
        assertFalse(desk.view.value.ready); assertNull(desk.view.value.id)
    }
    private class Transport : LiveSpeechSession {
        var connects = 0; var finishes = 0; var closes = 0
        override fun connect() { connects++ }
        override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
        override fun finish() { finishes++ }
        override fun close() { closes++ }
    }

    @Test fun defaultOnlyEnhancesMicrophoneAndCaptionsNeverCreateAnEnhancer() {
        for (kind in listOf(Kind.LISTEN, Kind.CAPTIONS)) {
            val context: Context = RuntimeEnvironment.getApplication()
            val settings = TranslationSettings(context)
            val store = ProbeStore(context, "denoise-$kind")
            val transport = Transport()
            var enhanced: Boolean? = null
            val desk = LiveDesk(context, store, settings, VoiceClips(), connect = { transport }, captionConnection = { transport },
                filter = { link, enabled -> enhanced = enabled; object : ListeningFilter, LiveSpeechSession by link {
                    override fun recheckOpening(sendOpening: () -> Unit): Boolean { sendOpening(); return false }
                } }, microphone = { _ -> object : AudioInputSource {
                    override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                        onStarted(); request.end.set(End.RELEASED); return request.empty(Verdict.SIGNAL)
                    }
                } })
            val request = requireNotNull(store.begin(kind, PlaybackTarget.SELF, null, null, null, "qa.player"))
            assertEquals(Verdict.SIGNAL, desk.record(request).verdict)
            assertEquals(kind == Kind.LISTEN, enhanced)
            assertEquals(1, transport.connects); assertEquals(1, transport.finishes); assertEquals(1, transport.closes)
            assertFalse(desk.view.value.ready); assertNull(desk.view.value.id)
        }
    }

    @Test fun localInitializationFailureClosesTransportAndDoesNotBlameQwen() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = ProbeStore(context, "denoise-init-failure")
        val transport = Transport()
        val desk = LiveDesk(context, store, TranslationSettings(context), VoiceClips(), connect = { transport },
            filter = { _, _ -> throw AudioProcessingFailure(IllegalStateException("bad model")) },
            microphone = { error("must not start microphone after processing failure") })
        val request = requireNotNull(store.begin(Kind.LISTEN, PlaybackTarget.SELF, null, null, null))
        assertEquals(Verdict.FAILED, desk.record(request).verdict)
        assertEquals("AUDIO_PROCESSING", desk.view.value.issue?.name)
        assertEquals(0, transport.connects); assertEquals(1, transport.closes)
        assertFalse(desk.view.value.ready); assertNull(desk.view.value.id)
    }
}
