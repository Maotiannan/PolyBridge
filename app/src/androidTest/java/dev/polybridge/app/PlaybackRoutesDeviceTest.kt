package dev.polybridge.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
        assumeTrue(InstrumentationRegistry.getArguments().getString("testRoutes") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = openProjectLauncher().application
        val prefix = "qa-routes-" + UUID.randomUUID()
        val sandbox = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(prefix + name, mode)
        }
        val settings = TranslationSettings(sandbox)
        assertTrue(settings.state.value.quietSpeaker)
        assertTrue(settings.state.value.splitPlayback)
        lateinit var voice: ConversationVoice
        inst.runOnMainSync { voice = ConversationVoice(sandbox, settings) {} }
        try {
            for (language in listOf("fr-FR", "zh-CN")) {
                inst.runOnMainSync { voice.phrase("tickets-" + language.substringBefore('-')) }
                val until = SystemClock.elapsedRealtime() + 4500
                while (language !in settings.lastRoutes.value && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
                val actual = requireNotNull(settings.lastRoutes.value[language]) { "媒体播放器未报告实际设备" }
                assertTrue(actual.type > 0)
                inst.sendStatus(0, Bundle().apply {
                    putString("observedRoute", "language=$language type=" + actual.type + " matched=" + actual.matchedPreference)
                })
                inst.runOnMainSync { voice.stop() }
            }
        } finally {
            inst.runOnMainSync { voice.close() }
            app.deleteSharedPreferences(prefix + "translator")
            app.deleteSharedPreferences(prefix + "audio_roles")
        }
    }
}
