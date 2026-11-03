package dev.polybridge.app

import android.app.Application
import android.content.Context
import android.media.AudioRecord
import dev.polybridge.app.audio.AndroidAudioInput
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidProbeApiTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application

    @Test fun launcherStartsWithoutOpeningTheMicrophone() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            val activity = it.get()
            assertFalse(activity.isFinishing)
            assertNull((activity.application as PolyBridgeApp).store.active.value)
        }
    }

    @Test fun staleWorkerCannotFinishAnotherSessionAndInterruptedTurnsRecover() {
        app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ProbeStore(app)
        val a = store.begin(Kind.HOLD, PlaybackTarget.SELF, null, Side.CHINESE, "test")!!
        assertNull(store.begin(Kind.MICROPHONE, PlaybackTarget.SELF, null, null, null))
        val recovered = ProbeStore(app)
        assertNull(recovered.active.value)
        assertEquals(Verdict.INTERRUPTED, recovered.report.value.probes.single().verdict)
        assertEquals(TurnStatus.FAILED, recovered.chat.value.turns.single().status)
        store.finish(a.empty(Verdict.CANCELLED))
        val b = store.begin(Kind.MICROPHONE, PlaybackTarget.SELF, null, null, "test")!!
        store.finish(a.empty(Verdict.SIGNAL))
        assertEquals(b.id, store.active.value?.id)
        store.finish(b.empty(Verdict.NO_DATA))
        assertEquals(2, ProbeStore(app).report.value.probes.size)
    }

    @Test fun releaseBeforeRecorderStartsDoesNotAcquireTheMicrophone() {
        val r = request(Kind.HOLD)
        r.end.set(End.CANCELLED)
        var started = false
        val result = AndroidAudioInput(app).measure(r, null) { started = true }
        assertEquals(Verdict.CANCELLED, result.verdict)
        assertFalse(started)
    }

    @Test fun playbackRequiresAUserProjection() {
        val result = AndroidAudioInput(app).measure(request(Kind.PLAYBACK), null) { fail("unexpected recording") }
        assertEquals(Verdict.DENIED, result.verdict)
        assertEquals("NO_PROJECTION", result.errorCode)
    }

    @Test fun simulatedAudioRecordFeedsTheProductionMeter() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val r = request(Kind.HOLD)
        var reads = 0
        ShadowAudioRecord.setSourceProvider { _: AudioRecord ->
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(buffer: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    java.util.Arrays.fill(buffer, offset, offset + size, 16384.toShort())
                    if (++reads == 10) r.end.set(End.RELEASED)
                    return size
                }
            }
        }
        try {
            val result = AndroidAudioInput(app).measure(r, null) {}
            assertEquals(result.errorCode, Verdict.SIGNAL, result.verdict)
            assertEquals(-6.0206, result.signal.rmsDbfs!!, 0.001)
            assertTrue(result.signal.samples > 0)
            assertNull(result.systemSilenced)
        } finally { ShadowAudioRecord.clearSource() }
    }

    private fun request(kind: Kind) = ProbeRequest("fixture", kind, PlaybackTarget.SELF, null,
        if (kind == Kind.HOLD) Side.CHINESE else null, 123, "test")
}
