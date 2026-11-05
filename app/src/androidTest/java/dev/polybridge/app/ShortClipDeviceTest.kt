package dev.polybridge.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import dev.polybridge.app.audio.SpeechGate
import dev.polybridge.evaluation.noise.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Injects approved fixture PCM into shipping Android models; no mic, playback, account or history. */
@RunWith(AndroidJUnit4::class)
class ShortClipDeviceTest {
    @Before fun foreground() { openProjectLauncher() }
    @Test fun measureAllApprovedClipsWithBothShippingFilterModes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        fun read(name: String) = inst.context.assets.open("short/$name").use { it.readBytes() }
        val manifest = readManifest(read("manifest.json").toString(Charsets.UTF_8))
        assertEquals(10, manifest.clips.size)
        val results = manifest.clips.flatMap { clip -> listOf(false, true).map { enhanced ->
            evaluateClip(clip, enhanced, ::read, { sink, mode -> SpeechGate(inst.targetContext, sink, mode) })
        } }
        inst.sendStatus(0, Bundle().apply {
            putString("shortClipReport", GsonBuilder().create().toJson(ClipReport("Android", results)))
        })
        // Diagnostics, not cloud accuracy acceptance. Structural failures throw inside evaluateClip.
        assertEquals(20, results.size)
        assertTrue("audio must stream throughout", results.all { it.maxBufferedMs <= 500 })
    }
}
