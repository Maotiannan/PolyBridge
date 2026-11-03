package dev.polybridge.app

import dev.polybridge.app.audio.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeadsetPreferencesTest {
    @Test fun rolesSurviveRecreationWithoutStoringTheBluetoothAddress() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = HeadsetPreferences(app)
        val address = "FA:12:34:56:78:90"
        val identity = requireNotNull(prefs.identity(address))
        assertNotEquals(address, identity)
        assertEquals(identity, prefs.identity(address.lowercase()))
        assertNull(prefs.identity("02:00:00:00:00:00"))
        assertNull(prefs.identity(""))
        prefs.assign(HeadsetRole.CHINESE, identity)
        val restored = HeadsetPreferences(app)
        assertEquals(identity, restored.assignments.value[HeadsetRole.CHINESE])
        assertNull(restored.assignments.value[HeadsetRole.FRENCH])
        assertFalse(app.getSharedPreferences("audio_roles", 0).all.toString().contains(address))
        restored.assign(HeadsetRole.CHINESE, null)
        assertNull(HeadsetPreferences(app).assignments.value[HeadsetRole.CHINESE])
    }
}
