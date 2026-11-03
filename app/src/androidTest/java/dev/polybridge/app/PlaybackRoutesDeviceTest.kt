package dev.polybridge.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.ConversationVoice
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaybackRoutesDeviceTest {
    @Test fun reportsTheActualOutputForBothSpokenLanguages() {
        val args = InstrumentationRegistry.getArguments()
        val virtualVolume = Build.HARDWARE in setOf("ranchu", "goldfish") && args.getString("testArchive") == "true"
        assumeTrue(virtualVolume || args.getString("testRoutes") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = openProjectLauncher().application
        val prefix = "qa-routes-" + UUID.randomUUID()
        val sandbox = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(prefix + name, mode)
        }
        val settings = TranslationSettings(sandbox)
        assertTrue(settings.state.value.splitPlayback)
        val audio = app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val original = audio.getStreamVolume(stream)
        val levels = if (virtualVolume) listOf(1, audio.getStreamMaxVolume(stream)) else listOf(original)
        lateinit var voice: ConversationVoice
        inst.runOnMainSync { voice = ConversationVoice(sandbox, settings) {} }
        try {
            for (level in levels) {
              if (virtualVolume) audio.setStreamVolume(stream, level, 0)
              for (language in listOf("fr-FR", "zh-CN")) {
                val previous = settings.lastRoutes.value[language]?.playbackId
                inst.runOnMainSync { voice.phrase("tickets-" + language.substringBefore('-')) }
                val until = SystemClock.elapsedRealtime() + 7000
                while (settings.lastRoutes.value[language]?.let { it.playbackId != previous && it.positionMs >= 100 } != true &&
                    SystemClock.elapsedRealtime() < until) SystemClock.sleep(35)
                val actual = requireNotNull(settings.lastRoutes.value[language]) { "媒体播放器未报告实际设备" }
                assertTrue(actual.type > 0)
                assertNotEquals(previous, actual.playbackId)
                assertTrue(actual.positionMs >= 100)
                assertEquals(1f, actual.gain, 0f)
                if (virtualVolume) assertEquals(level, audio.getStreamVolume(stream))
                inst.sendStatus(0, Bundle().apply {
                    putString("observedRoute", "language=$language type=${actual.type} gain=${actual.gain} systemIndex=${audio.getStreamVolume(stream)}")
                })
                inst.runOnMainSync { voice.stop() }
              }
            }
        } finally {
            inst.runOnMainSync { voice.close() }
            if (virtualVolume) audio.setStreamVolume(stream, original, 0)
            app.deleteSharedPreferences(prefix + "translator")
            app.deleteSharedPreferences(prefix + "audio_roles")
        }
    }
}
