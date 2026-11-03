package dev.polybridge.app

import android.graphics.Rect
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.*
import dev.polybridge.app.ui.*
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InputInteractionDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 6000
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { message }; SystemClock.sleep(35) }
    }
    private fun node(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = inst.uiAutomation.rootInActiveWindow ?: return null
        val list = ArrayDeque(listOf(root))
        while (list.isNotEmpty()) {
            val item = list.removeFirst()
            if (item.packageName == "dev.polybridge.app" && match(item)) return item
            for (i in 0 until item.childCount) item.getChild(i)?.let(list::addLast)
        }
        return null
    }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureQaScreens") != "true") return
        val shot = inst.uiAutomation.takeScreenshot()
        inst.targetContext.cacheDir.resolve(name).outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
    private val noop = TalkActions({ null }, { _, _ -> }, {}, {}, {})

    @Test fun firstBottomSwipeStaysInTheAppAndSecondGoesHome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyVivoDock") == "true")
        val activity = openProjectLauncher()
        val app = activity.application as PolyBridgeApp
        assumeTrue(android.os.Build.MODEL == "V2436A" && app.settings.state.value.immersiveTalk &&
            activity.resources.configuration.screenWidthDp >= 600)
        val nav = WindowInsets.Type.navigationBars()
        fun visible() = activity.window.decorView.rootWindowInsets.isVisible(nav)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val resumedPackage = Regex("""\bu\d+\s+([^\s/]+)/""")
        fun current() = shell("dumpsys activity activities").lineSequence()
            .firstOrNull { it.contains("topResumedActivity") }?.let { resumedPackage.find(it)?.groupValues?.get(1) }
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val launcher = activity.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        requireNotNull(launcher)
        val display = activity.windowManager.currentWindowMetrics.bounds
        fun swipe() {
            val x = display.centerX()
            shell("input -d 0 swipe $x ${display.bottom - 2} $x ${display.centerY()} 220")
        }
        try {
            waitFor("面对面未隐藏导航栏") { !visible() && current() == app.packageName }
            swipe()
            SystemClock.sleep(330)
            assertEquals("首次上滑离开了应用", app.packageName, current())
            swipe()
            waitFor("第二次上滑没有回到系统桌面") { current() == launcher }
            inst.sendStatus(0, Bundle().apply { putString("immersiveSwipes", "first retained app; second reached system home") })
        } finally { openProjectLauncher() }
    }

    @Test fun foldedPanelsKeepTheFingerThroughDriftCancelAndReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inputInteractions") == "true")
        val activity = openProjectLauncher()
        val held = mutableStateOf<ProbeRequest?>(null)
        val finished = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val actions = TalkActions({ side ->
            ProbeRequest(UUID.randomUUID().toString(), Kind.HOLD, PlaybackTarget.SELF, null, side, 0, null).also {
                it.sampling.value = true; held.value = it
            }.id
        }, { _, cancel -> finished += cancel; held.value = null }, {}, {}, {})
        inst.runOnMainSync { activity.setContent { StudioTheme {
            ImmersiveTalk(true)
            Box(Modifier.fillMaxSize().semantics { paneTitle = "测试对坐录音" }) {
                FoldTalk(Conversation(), held.value, true, actions, {}, {})
            }
        } } }
        waitFor("测试界面未就绪") { node { it.paneTitle == "测试对坐录音" } != null }
        inst.uiAutomation.waitForIdle(230, 2500)
        val density = activity.resources.displayMetrics.density
        for (side in listOf(Side.CHINESE, Side.FRENCH)) {
            val zh = side == Side.CHINESE
            val label = if (zh) "中文，按住说话" else "Français, maintenir pour parler"
            val bounds = Rect()
            waitFor(label) { node { it.contentDescription?.toString() == label }?.getBoundsInScreen(bounds); !bounds.isEmpty }
            val display = activity.windowManager.currentWindowMetrics.bounds
            if (zh) assertTrue("dock guard missing", display.height() - bounds.bottom >= 45 * density)
            for (releaseInRed in listOf(false, true)) {
                val prior = finished.size
                val down = SystemClock.uptimeMillis()
                fun finger(action: Int, x: Float, y: Float) {
                    val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { check(inst.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
                }
                val x = bounds.exactCenterX()
                val y = bounds.exactCenterY()
                val above = if (zh) bounds.top - 28 * density else bounds.bottom + 28 * density
                finger(MotionEvent.ACTION_DOWN, x, y)
                waitFor("长按未开始 side=$side attempts=$prior bounds=$bounds") { held.value?.side == side }
                SystemClock.sleep(1000)
                finger(MotionEvent.ACTION_MOVE, x + 75 * density, y + (if (zh) -3 else 3) * density)
                assertEquals(prior, finished.size)
                finger(MotionEvent.ACTION_MOVE, x, above)
                waitFor("red cancel target missing") {
                    node { it.stateDescription?.toString() == (if (zh) "松开取消" else "Relâcher pour annuler") } != null
                }
                if (zh && releaseInRed) screenshot("qa-fold-cancel.png")
                if (!releaseInRed) {
                    finger(MotionEvent.ACTION_MOVE, x, y)
                    waitFor("return did not disarm cancellation") {
                        node { it.stateDescription?.toString() == (if (zh) "松开发送 · 上滑取消" else "Relâcher pour envoyer") } != null
                    }
                }
                finger(MotionEvent.ACTION_UP, x, if (releaseInRed) above else y)
                waitFor("release lost") { finished.size > prior }
                assertEquals(releaseInRed, finished.last())
            }
        }
        val after = activity.window.decorView.rootWindowInsets
        assertFalse("system navigation stole the long press", after.isVisible(WindowInsets.Type.navigationBars()))
        inst.sendStatus(0, Bundle().apply { putString("holdGestures", "both orientations; long hold, 75dp drift, red cancel and re-entry; results=" + finished) })
    }

    @Test fun stopIconTracksCaptureFramesAndFallsSilent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inputInteractions") == "true")
        val activity = openProjectLauncher()
        val app = activity.application as PolyBridgeApp
        val req = ProbeRequest(UUID.randomUUID().toString(), Kind.LISTEN, PlaybackTarget.SELF, null, null, 0, null)
        val amplitude = AtomicInteger(0)
        val session = object : LiveSpeechSession {
            override fun connect() = Unit
            override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
            override fun finish() = Unit
            override fun close() = Unit
        }
        val desk = LiveDesk(app, app.store, app.settings, VoiceClips(), connect = { session }, microphone = { frame ->
            object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted()
                    while (request.end.get() == null) {
                        val samples = ShortArray(320) { if (it % 2 == 0) amplitude.get().toShort() else (-amplitude.get()).toShort() }
                        frame(samples, samples.size, 16000); SystemClock.sleep(20)
                    }
                    return request.empty(Verdict.SIGNAL)
                }
            }
        })
        inst.runOnMainSync { activity.setContent { StudioTheme { ListeningPanel(app, Conversation(), req, noop, {}, {}, {}) } } }
        val exec = Executors.newSingleThreadExecutor()
        val task = exec.submit<ProbeEntry> { desk.record(req) }
        try {
            fun shown() = node { it.rangeInfo != null }?.rangeInfo?.current ?: -1f
            waitFor("silent meter missing") { shown() == 0f }
            amplitude.set(650)
            waitFor("quiet sample not displayed") { shown() in .25f.. .65f }
            val quiet = shown()
            amplitude.set(12000)
            waitFor("loud sample not displayed") { shown() > .85f }
            val loud = shown()
            amplitude.set(0)
            waitFor("meter kept moving in silence") { shown() == 0f }
            inst.sendStatus(0, Bundle().apply { putString("visibleMeter", "silence=0 quiet=$quiet loud=$loud returned=0") })
        } finally { req.end.set(End.RELEASED); task.get(8, TimeUnit.SECONDS); exec.shutdownNow() }
    }
}
