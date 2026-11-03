package dev.polybridge.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.TranslatedAudioShare
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenCaptionsDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    @Test fun packageHasNoKeyboardOrAudioPickerAndSharesReadOnlyFrenchAudio() {
        val ctx = inst.targetContext
        val services = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        assertFalse(services.any { it.permission == "android.permission.BIND_INPUT_METHOD" })
        val share = TranslatedAudioShare(ctx).create(ctx.assets.open("phrasebook/tickets-fr.wav").use { it.readBytes() })
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.flags)
        @Suppress("DEPRECATION") val uri = requireNotNull(share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("RIFF", ctx.contentResolver.openInputStream(uri)!!.use { String(it.readNBytes(4)) })
        assertTrue(ctx.packageManager.queryIntentActivities(share.setPackage(ctx.packageName), 0).isEmpty())
    }

    private fun waitFor(label: String, ms: Long = 12000, match: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!match()) { check(SystemClock.elapsedRealtime() < until) { label }; SystemClock.sleep(100) }
    }

    private fun find(pkg: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val roots = inst.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(inst.uiAutomation.rootInActiveWindow)
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.packageName?.toString() == pkg && predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
        }
        return null
    }
    private fun click(pkg: String, label: String) = waitFor("没有找到按钮 " + label) {
        var node = find(pkg) { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@waitFor true
            node = node.parent
        }
        false
    }

    private fun allowTestProjection() {
        val root = inst.uiAutomation.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in setOf("com.android.systemui", "com.vivo.permissionmanager", "com.android.permissioncontroller")) return
        val fixture = find(pkg) { it.isVisibleToUser && (it.text?.toString()?.contains("字幕样本") == true ||
            it.contentDescription?.toString()?.contains("字幕样本") == true) }
        val next = fixture ?: find(pkg) { it.isVisibleToUser && it.isEnabled &&
            (it.text?.toString() in setOf("立即开始", "开始录制", "开始共享", "Start now", "Share screen", "开始", "继续", "下一步", "Next") ||
                it.viewIdResourceName?.substringAfterLast('/') == "start_button") } ?: return
        val rect = android.graphics.Rect()
        next.getBoundsInScreen(rect)
        if (!rect.isEmpty) shell("input tap " + rect.centerX() + " " + rect.centerY())
    }

    @Test fun anotherApplicationsAudioShowsLiveBilingualTextAboveThatApplication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudCaptions") == "true")
        val ctx = inst.targetContext
        val own = ctx.packageName
        val fixture = inst.context.packageName
        val component = fixture + "/dev.polybridge.fixture.CaptionFixtureActivity"
        assertNotEquals(ctx.applicationInfo.uid, ctx.packageManager.getApplicationInfo(fixture, 0).uid)
        check(Settings.canDrawOverlays(ctx)) { "请先从系统授予本项目悬浮字幕权限" }
        val app = openProjectLauncher().application as PolyBridgeApp
        val previous = app.settings.state.value.captionApp
        val ui = inst.uiAutomation
        val flags = ui.serviceInfo.flags
        fun flags(value: Int) { ui.serviceInfo = ui.serviceInfo.apply { this.flags = value } }
        var id: String? = null
        try {
            flags(flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)
            check(shell("run-as $fixture /system/bin/pm enable --user 0 " + component).contains("enabled"))
            // The Android single-app consent sheet lists recent tasks, which are distinct from installed apps.
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n " + component)
            waitFor("独立样本播放器没有启动") { find(fixture) { it.text?.toString() == "独立媒体播放器" } != null }
            openProjectLauncher()
            app.settings.captionApp(fixture)
            check(app.store.chat.value.turns.size < 94) { "测试需要六条空闲历史位置" }
            click(own, "屏幕字幕")
            waitFor("样本应用未显示") { find(own) { it.text?.toString() == "PolyBridge 字幕样本" } != null }
            click(own, "从应用播放中采集")
            waitFor("等待系统授权和媒体采集", 90000) {
                if (app.store.active.value == null) allowTestProjection()
                app.store.active.value?.let { it.kind == Kind.CAPTIONS && it.sampling.value } == true
            }
            id = app.store.active.value!!.id
            waitFor("未打开独立媒体应用") { find(fixture) { it.text?.toString() == "独立媒体播放器" } != null }
            waitFor("其他应用上未出现字幕条") { find(own) { it.contentDescription?.toString() == "停止屏幕字幕" } != null }
            click(fixture, "播放禁止共享的样本")
            SystemClock.sleep(3200)
            assertTrue(app.live.view.value.turnIds.isEmpty())
            click(fixture, "播放纯雨声")
            SystemClock.sleep(8500)
            assertTrue("纯雨声生成了原文或译文", app.live.view.value.turnIds.isEmpty() && app.live.view.value.drafts.isEmpty())
            for ((label, side) in listOf("播放法语样本" to Side.FRENCH, "播放中文样本" to Side.CHINESE)) {
                click(fixture, label)
                val until = SystemClock.elapsedRealtime() + 30000
                var partialSeen = false
                while (app.live.view.value.lastLine?.side != side && SystemClock.elapsedRealtime() < until) {
                    if (app.live.view.value.partial?.side == side) partialSeen = true
                    SystemClock.sleep(30)
                }
                val line = requireNotNull(app.live.view.value.lastLine) { "没有收到固定语音译文" }
                assertEquals(side, line.side)
                assertTrue("同语种或错误译文 " + line.target, if (side == Side.FRENCH) "票" in line.target else line.target.contains("gare", true))
                waitFor("其他应用上没有出现这句原文与译文") {
                    find(own) { it.text?.toString() == line.source } != null && find(own) { it.text?.toString() == line.target } != null &&
                        find(fixture) { it.text?.toString() == "独立媒体播放器" } != null
                }
                inst.sendStatus(0, Bundle().apply { putString("externalCaption", "${line.side} ${line.source} | ${line.target} partial=$partialSeen") })
                if (side == Side.FRENCH && InstrumentationRegistry.getArguments().getString("captureQaScreens") == "true") {
                    val shot = inst.uiAutomation.takeScreenshot()
                    ctx.cacheDir.resolve("qa-external-captions.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
            }
            assertEquals(2, app.live.view.value.turnIds.size)
            assertFalse(app.live.view.value.speaking)
            val previousIds = app.live.view.value.turnIds.toSet()
            click(fixture, "播放换气长语音")
            waitFor("长语音换气前后的原文和译文都应保存", 40000) {
                val lines = app.store.chat.value.turns.filter { it.live?.captureId == id && it.id !in previousIds }
                val source = lines.joinToString(" ") { it.sourceText }.lowercase()
                val target = lines.joinToString(" ") { it.translatedText }
                source.indexOf("billet") >= 0 && source.indexOf("gare") > source.indexOf("billet") &&
                    target.indexOf("票") >= 0 && target.indexOf("站") > target.indexOf("票")
            }
            click(own, "查看字幕全文")
            waitFor("悬浮全文缺少换气后的内容") {
                find(own) { node -> node.text?.toString()?.let { "票" in it && "站" in it && it.contains("gare", true) } == true } != null
            }
            click(own, "停止屏幕字幕")
            waitFor("未释放共享会话", 18000) { app.store.active.value == null && find(own) { it.contentDescription?.toString() == "停止屏幕字幕" } == null }
        } finally {
            app.store.active.value?.takeIf { it.kind == Kind.CAPTIONS }?.let { id = it.id; app.live.stopNow(it) }
            id?.let { session -> app.store.changeChat { chat -> chat.copy(turns = chat.turns.filterNot { it.id.startsWith("live-" + session + "-") }) } }
            app.settings.captionApp(previous)
            shell("run-as $fixture /system/bin/pm disable --user 0 " + component)
            flags(flags)
            openProjectLauncher()
        }
    }
}
