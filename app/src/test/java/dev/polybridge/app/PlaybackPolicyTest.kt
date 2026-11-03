package dev.polybridge.app

import android.media.AudioDeviceInfo
import dev.polybridge.app.audio.playbackGain
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPolicyTest {
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    @Test fun compensatesOnlyForTheSpeakersExtraGain() {
        val result = playbackGain(true, speaker, 9, 0) { index -> if (index == 1) -48f else -8f }
        assertEquals(.01f, result, .0001f)
        assertEquals(1f, playbackGain(true, speaker, 1, 1) { error("already at minimum") }, 0f)
        assertEquals(0f, playbackGain(true, speaker, 0, 0) { error("muted") }, 0f)
    }

    @Test fun respectsHeadsetVolumeAndWaitsForAnActualRoute() {
        assertEquals(1f, playbackGain(true, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 14, 0) { error("headset") }, 0f)
        assertEquals(1f, playbackGain(false, speaker, 14, 0) { error("disabled") }, 0f)
        assertEquals(0f, playbackGain(true, null, 14, 0) { error("unknown device") }, 0f)
    }
}
