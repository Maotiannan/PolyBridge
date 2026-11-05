package dev.polybridge.audio

import com.google.gson.GsonBuilder
import dev.polybridge.evaluation.noise.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShortClipBenchmarkTest {
    @Test fun alteredFixturesAndWrongLengthsAreRejectedBeforeProcessing() {
        val fixtures = File(root, "evaluation/noise/fixtures/short")
        val clip = readManifest(File(fixtures, "manifest.json").readText()).clips.first()
        assertThrows(IllegalArgumentException::class.java) {
            evaluateClip(clip, false, { File(fixtures, it).readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } },
                { _, _ -> error("invalid fixture must not reach model") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluateClip(clip.copy(samples = 400000), false, { File(fixtures, it).readBytes() },
                { _, _ -> error("invalid clock must not reach model") })
        }
    }
    @Test fun measureAllApprovedClipsWithBothShippingFilterModes() {
        val fixtures = File(root, "evaluation/noise/fixtures/short")
        val manifest = readManifest(File(fixtures, "manifest.json").readText())
        assertEquals(10, manifest.clips.size)
        val results = manifest.clips.flatMap { clip -> listOf(false, true).map { enhanced ->
            evaluateClip(clip, enhanced, { File(fixtures, it).readBytes() }, { sink, mode -> gate(sink, mode) }).also {
                println("clip=${it.clip} enhanced=${it.enhanced} issues=${it.acousticIssues} turns=${it.turns}")
            }
        } }
        val report = File(root, "audio/processing/build/reports/short-clips.json")
        report.parentFile.mkdirs()
        report.writeText(GsonBuilder().setPrettyPrinting().create().toJson(ClipReport("JVM", results)))
        // Acoustic findings remain in the report, including failures. Passing this test proves only
        // valid fixtures, unchanged sample clocks, streaming and lifecycle, NOT acoustic acceptance.
        assertEquals(20, results.size)
        assertTrue("audio must stream throughout, not only at the end", results.all { it.maxBufferedMs <= 500 })
    }
}
