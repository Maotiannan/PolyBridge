package dev.polybridge.app

import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.polybridge.app.audio.HeadsetRoute
import dev.polybridge.app.audio.companionOutput
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeadsetRouteTest {
    private val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
    private val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
    private val request = ProbeRequest("fixture", Kind.MICROPHONE, PlaybackTarget.SELF, 7, null, 0, null)

    @Test fun soloUnknownAddressIsAllowedButMultipleHeadsetsNeedAnExactMatch() {
        assertEquals("bb", companionOutput("BB", listOf("aa", "bb")) { it })
        assertEquals("aa", companionOutput("", listOf("aa")) { it })
        assertNull(companionOutput("", listOf("aa", "bb")) { it })
        assertNull(companionOutput("xx", listOf("aa")) { it })
        assertNull(companionOutput("02:00:00:00:00:00", listOf("aa", "bb")) { it })
    }

    @Test fun selectedHeadsetGetsTheCommunicationRouteOnlyDuringCapture() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(headset))
        HeadsetRoute(audio, headset).use {
            assertNull(it.connect(request))
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
            assertEquals(headset.id, audio.communicationDevice?.id)
        }
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(audio.communicationDevice)
    }

    @Test fun existingCallAndCancelledSetupLeaveTheAudioModeAlone() {
        audio.mode = AudioManager.MODE_IN_CALL
        HeadsetRoute(audio, headset).use { assertEquals("COMMUNICATION_BUSY", it.connect(request)?.errorCode) }
        assertEquals(AudioManager.MODE_IN_CALL, audio.mode)
        audio.mode = AudioManager.MODE_NORMAL
        request.end.set(End.CANCELLED)
        HeadsetRoute(audio, headset).use { assertEquals(Verdict.CANCELLED, it.connect(request)?.verdict) }
        assertNull(audio.communicationDevice)
    }
}
