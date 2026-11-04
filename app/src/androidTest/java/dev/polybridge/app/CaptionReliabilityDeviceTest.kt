package dev.polybridge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.ui.CaptionContent
import dev.polybridge.app.ui.TranscriptRow
import dev.polybridge.app.audio.*
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class CaptionReliabilityDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T : View> named(view: View, description: String): T =
        tree(view).first { it.contentDescription == description } as T
    private fun rows(count: Int, side: Side = Side.FRENCH) = listOf(TranscriptRow(LiveUpdate.Caption("same", side,
        (1..count).joinToString("\n") { "Phrase française numéro $it." },
        (1..count).joinToString("\n") { "这是第 $it 句中文。" }), null))
    private fun main(block: () -> Unit) { inst.runOnMainSync(block); inst.waitForIdleSync() }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            var passed = false; main { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }

    @Test fun existingHistoryIsReadOnlyAndKnownLoopsCanBeFlaggedWithoutExportingText() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectCaptionQuality") == "true")
        val app = inst.targetContext.applicationContext as PolyBridgeApp
        val before = app.store.chat.value
        val suspect = before.turns.filter { Regex("come on", RegexOption.IGNORE_CASE).findAll(it.sourceText).count() >= 8 }
        assumeTrue("现场条目已不在当前历史，不能重新验证旧条目", suspect.isNotEmpty())
        assertTrue("现场循环未触发质量检查", suspect.all { CaptionQuality.repetitive(it.sourceText) })
        assertEquals(before, app.store.chat.value)
        inst.sendStatus(0, Bundle().apply { putString("existingHistoryQuality", "matched=${suspect.size} flagged=true modified=false") })
    }

    @Test fun unconfirmedDraftAndLaterSentenceArePersistedInAnIsolatedStore() {
        val app = inst.targetContext.applicationContext as PolyBridgeApp
        val name = "qa-caption-quality-" + java.util.UUID.randomUUID()
        val store = ProbeStore(app, name)
        lateinit var emit: (LiveUpdate) -> Unit
        val request = requireNotNull(store.begin(Kind.CAPTIONS, PlaybackTarget.SELF, null, null, null, "qa.player"))
        val link: ((LiveUpdate) -> Unit) -> LiveSpeechSession = { receiver ->
            emit = receiver
            object : LiveSpeechSession {
                override fun connect() = Unit
                override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
                override fun finish() = Unit
                override fun close() = Unit
            }
        }
        val desk = LiveDesk(app, store, app.settings, VoiceClips(), captionConnection = link, microphone = {
            object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: android.media.projection.MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted()
                    emit(LiveUpdate.Caption("unconfirmed", Side.CHINESE, "", "", 20, TranslationIssue.UNRELIABLE))
                    emit(LiveUpdate.Finished("next", Side.FRENCH, 500, "Merci", "谢谢", byteArrayOf()))
                    request.end.set(End.RELEASED)
                    return request.empty(Verdict.SIGNAL)
                }
            }
        })
        try {
            val report = desk.record(request); store.finish(report)
            assertEquals(Verdict.SIGNAL, report.verdict)
            val saved = ProbeStore(app, name).chat.value.turns
            assertEquals(2, saved.size)
            assertEquals(TranslationIssue.UNRELIABLE, saved[0].issue)
            assertEquals(TurnStatus.FAILED, saved[0].status)
            assertEquals("Merci", saved[1].sourceText)
            assertEquals(TurnStatus.READY, saved[1].status)
        } finally {
            app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().commit()
            app.deleteSharedPreferences(name)
        }
    }

    @Test fun realOverlayKeepsLongCaptionTailsVisibleAndHonorsManualScrolling() {
        val activity = openProjectLauncher()
        val wm = activity.getSystemService(WindowManager::class.java)
        val previousOp = Regex("""SYSTEM_ALERT_WINDOW:\s*(allow|ignore|deny|default|foreground)""")
            .find(shell("appops get ${activity.packageName} SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        lateinit var content: CaptionContent
        var attached = false
        val width = (360 * activity.resources.displayMetrics.density).toInt()
            .coerceAtMost(wm.currentWindowMetrics.bounds.width() - 48)
        try {
            shell("appops set ${activity.packageName} SYSTEM_ALERT_WINDOW allow")
            main {
                content = CaptionContent(activity) {}
                wm.addView(content, WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 100 })
                attached = true
                content.render(rows(30))
            }
            val source = named<ScrollView>(content, "滚动字幕原文")
            val target = named<ScrollView>(content, "滚动字幕译文")
            waitFor("小窗仍停在开头") { source.scrollY > 0 && target.scrollY > 0 && !source.canScrollVertically(1) && !target.canScrollVertically(1) }
            main { content.render(rows(60)) }
            waitFor("增长的同一句没有跟随") { (source.getChildAt(0) as TextView).text.contains("numéro 60") && !source.canScrollVertically(1) }
            main {
                val now = SystemClock.uptimeMillis()
                val x = source.width / 2f
                for ((i, y) in listOf(12f, source.height * .45f, source.height * .8f).withIndex()) {
                    val event = MotionEvent.obtain(now, now + i * 30, if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, x, y, 0)
                    source.dispatchTouchEvent(event); event.recycle()
                }
                val cancel = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_CANCEL, x, source.height * .8f, 0)
                source.dispatchTouchEvent(cancel); cancel.recycle()
            }
            waitFor("手动上翻未显示回到最新") { named<View>(content, "回到最新字幕").visibility == View.VISIBLE }
            var previousY = 0
            main { previousY = source.scrollY; content.render(rows(80)) }
            waitFor("刷新打断了上翻") { source.scrollY == previousY && source.canScrollVertically(1) }
            main { named<View>(content, "回到最新字幕").performClick() }
            waitFor("回到最新未恢复") { !source.canScrollVertically(1) && !target.canScrollVertically(1) }
            main {
                val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                content.draw(Canvas(bitmap))
                activity.cacheDir.resolve("qa-caption-scroll.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                named<View>(content, "查看字幕全文").performClick()
            }
            val full = named<ScrollView>(content, "滚动字幕全文")
            waitFor("全文末尾不可见") { full.scrollY > 0 && !full.canScrollVertically(1) }
            main { assertTrue(full.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)) }
            var fullY = 0
            main { fullY = full.scrollY; content.render(rows(100)) }
            waitFor("全文上翻后跳走") { full.scrollY == fullY && full.canScrollVertically(1) }
            main { named<View>(content, "回到最新字幕").performClick() }
            waitFor("全文恢复跟随失败") { !full.canScrollVertically(1) }
            inst.sendStatus(0, Bundle().apply { putString("captionScroll", "compact=100-lines touchPause=true accessibilityPause=true resume=true fullTail=true") })
        } finally {
            main { if (attached) wm.removeViewImmediate(content) }
            shell("appops set ${activity.packageName} SYSTEM_ALERT_WINDOW $previousOp")
        }
    }

    @Test(timeout = 120000) fun realQwenRechecksAFrenchClipAfterASimulatedWrongLanguageLoop() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudCaptionRecovery") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        val provider = requireNotNull(app.settings.provider())
        val wave = app.assets.open("phrasebook/tickets-fr.wav").use { it.readBytes() }
        val pcm = requireNotNull(VoicePcm.wave(wave))
        val buffer = java.nio.ByteBuffer.wrap(wave).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, pcm.channels)
        val samples = ShortArray((pcm.length / 2L * 16000 / pcm.rate).toInt()) {
            buffer.getShort(pcm.offset + (it.toLong() * pcm.rate / 16000).toInt() * 2)
        }
        lateinit var emit: (LiveUpdate) -> Unit
        val output = CopyOnWriteArrayList<LiveUpdate>()
        var calls = 0
        RecoveringCaptionSession({ receiver ->
            emit = receiver
            object : LiveSpeechSession {
                override fun connect() = Unit
                override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
                override fun finish() = Unit
                override fun close() = Unit
            }
        }, { calls++; provider.recoverCaption(it) }, output::add, CaptionRecoveryPolicy(finishWaitMs = 30_000)).use { session ->
            session.send(samples, samples.size, 16000)
            val bad = LiveUpdate.Finished("qa-bad", Side.CHINESE, 0, "", "", byteArrayOf(),
                TranslationIssue.UNRELIABLE, samples.size * 1000L / 16000)
            emit(bad); emit(bad); session.finish()
            val result = output.filterIsInstance<LiveUpdate.Finished>().single()
            assertNull("复核失败: ${result.issue}", result.issue)
            assertEquals(Side.FRENCH, result.side)
            assertTrue(result.source.contains("billet", true)); assertTrue("票" in result.target)
            assertEquals(1, calls)
            inst.sendStatus(0, Bundle().apply { putString("captionRecovery", "calls=$calls language=${result.side} source=${result.source} translation=${result.target}") })
        }
    }
}
