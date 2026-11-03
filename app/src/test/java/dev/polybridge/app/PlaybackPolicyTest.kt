package dev.polybridge.app

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackPolicyTest {
    @Test fun upgradeDiscardsOnlyTheRetiredVolumePreference() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("translator", Context.MODE_PRIVATE)
        for (legacy in listOf(true, false)) {
            prefs.edit().putBoolean("quietSpeaker", legacy).putBoolean("splitPlayback", false)
                .putBoolean("privateReplay", true).commit()
            val settings = TranslationSettings(context).state.value
            assertFalse(prefs.contains("quietSpeaker"))
            assertFalse(settings.splitPlayback)
            assertTrue(settings.privateReplay)
        }
    }
}
