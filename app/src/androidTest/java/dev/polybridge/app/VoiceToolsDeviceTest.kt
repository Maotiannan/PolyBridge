package dev.polybridge.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.*
import dev.polybridge.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VoiceToolsDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    @Test fun importsAContentUriAndBuildsAReadOnlyAudioShare() {
        val context = inst.targetContext
        val asset = context.assets.open("phrasebook/tickets-fr.wav").use { it.readBytes() }
        val share = TranslatedAudioShare(context).create(asset)
        @Suppress("DEPRECATION") val uri = requireNotNull(share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("audio/wav", share.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.flags)
        assertEquals(context.packageName + ".speechfiles", uri.authority)
        val decoded = runBlocking { AudioFileDecoder(context).read(uri) }
        assertEquals(16000, ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        assertTrue(decoded.size > 30_000)
        val apps = context.packageManager.queryIntentActivities(share.setPackage(context.packageName), 0)
        assertTrue(apps.any { it.activityInfo.name == MainActivity::class.java.name })
    }

    @Test fun translatesImportedFrenchWithoutUsingTheMicrophone() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveQwen") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        val asset = app.assets.open("phrasebook/tickets-fr.wav").use { it.readBytes() }
        val outgoing = TranslatedAudioShare(app).create(asset)
        @Suppress("DEPRECATION") val uri = requireNotNull(outgoing.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val namespace = "qa-import-" + UUID.randomUUID()
        try {
            val store = ProbeStore(app,namespace)
            val pipeline = ConversationTranslator(store, CoroutineScope(Dispatchers.Unconfined), app.settings::provider)
            val wave = runBlocking { AudioFileDecoder(app).read(uri) }
            val id = pipeline.imported(Side.FRENCH, wave)
            val final = store.chat.value.turns.single { it.id == id }
            assertEquals(final.issue?.name, TurnStatus.READY, final.status)
            assertTrue(final.sourceText.contains("billet", true) && final.translatedText.contains("票"))
            assertNull(app.store.active.value)
            inst.sendStatus(0, Bundle().apply { putString("fileTranslation", final.sourceText + " | " + final.translatedText) })
        } finally { app.deleteSharedPreferences(namespace) }
    }

    @Test fun keyboardEditsACandidateAndInsertsIntoAnActualEditor() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testKeyboard") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        val ime = "dev.polybridge.app/.ime.TranslationKeyboard"
        val original = shell("settings --user 0 get secure default_input_method")
        require(original.matches(Regex("[a-zA-Z0-9_.$/]+"))) { "无法验证当前输入法" }
        val enabled = shell("settings --user 0 get secure enabled_input_methods").split(':').any { it.substringBefore(';') == ime }
        val ui = inst.uiAutomation
        val info = ui.serviceInfo
        val originalFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = info
        val id = "qa-ime-" + UUID.randomUUID()
        try {
            shell("ime enable --user 0 $ime")
            shell("ime set --user 0 $ime")
            check(shell("settings --user 0 get secure default_input_method") == ime)
            app.store.changeChat { it.typed(id, Side.CHINESE, "键盘测试").translated(id, "键盘测试", "Bonjour.") }
            click("通话辅助", AccessibilityWindowInfo.TYPE_APPLICATION)
            val host = node(AccessibilityWindowInfo.TYPE_APPLICATION, scroll = true) { it.isEditable && it.isVisibleToUser && !it.isPassword }
            val bounds = Rect()
            host.getBoundsInScreen(bounds)
            check(!bounds.isEmpty)
            shell("input tap " + bounds.centerX() + " " + bounds.centerY())
            keyboardAction({ it is Button && it.text == "最近译文" }) { check(it.performClick()) }
            val corrected = "Bonjour, deux billets."
            keyboardAction({ it is EditText && it.text.toString() == "Bonjour." }) {
                (it as EditText).setText(corrected)
            }
            keyboardAction({ it is Button && it.text == "插入译文" }) { check(it.performClick()) }
            node(AccessibilityWindowInfo.TYPE_APPLICATION) { it.isEditable && it.text?.toString() == corrected }
            assertEquals(1, app.store.chat.value.turns.count { it.id == id })
            inst.sendStatus(0, Bundle().apply { putString("ime", "Edited candidate committed into app editor; no send action") })
        } catch (error: Exception) {
            val imeState = shell("dumpsys input_method")
            val shown = Regex("mInputShown=(true|false)").find(imeState)?.groupValues?.get(1)
            inst.sendStatus(0, Bundle().apply {
                putString("imeState", "inputShown=" + shown + " windows=" + ui.windows.map { it.type })
            })
            throw error
        } finally {
            app.store.changeChat { it.copy(turns = it.turns.filterNot { t -> t.id == id }) }
            if (original != ime) shell("ime set --user 0 $original")
            if (!enabled && original != ime) shell("ime disable --user 0 $ime")
            info.flags = originalFlags
            ui.serviceInfo = info
            check(shell("settings --user 0 get secure default_input_method") == original)
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    private fun keyboardAction(match: (View) -> Boolean, action: (View) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        var windowState = ""
        do {
            var found = false
            inst.runOnMainSync {
                val roots = WindowInspector.getGlobalWindowViews()
                fun type(view: View) = (view.layoutParams as? WindowManager.LayoutParams)?.type
                windowState = roots.joinToString { type(it).toString() + "/" + it.isShown }
                val queue = ArrayDeque(roots.filter { type(it) == WindowManager.LayoutParams.TYPE_INPUT_METHOD })
                while (queue.isNotEmpty()) {
                    val view = queue.removeFirst()
                    if (view.isShown && match(view)) { action(view); found = true; break }
                    if (view is ViewGroup) for (i in 0 until view.childCount) queue.addLast(view.getChildAt(i))
                }
            }
            if (found) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("翻译键盘未显示预期控件，当前应用窗口 " + windowState)
    }

    private fun node(type: Int, scroll: Boolean = false, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 7000
        do {
            var container: AccessibilityNodeInfo? = null
            val list = ArrayDeque(inst.uiAutomation.windows.filter { it.type == type }.mapNotNull { it.root })
            while (list.isNotEmpty()) {
                val item = list.removeFirst()
                if (item.packageName == "dev.polybridge.app" && match(item)) return item
                if (item.packageName == "dev.polybridge.app" && item.isScrollable) container = item
                for (i in 0 until item.childCount) item.getChild(i)?.let(list::addLast)
            }
            if (scroll) container?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(if (scroll) 250 else 100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("应用或翻译键盘中未出现预期控件")
    }
    private fun click(label: String, type: Int) {
        val bounds = Rect()
        var target: AccessibilityNodeInfo? = node(type) {
            it.getBoundsInScreen(bounds)
            it.isVisibleToUser && !bounds.isEmpty && (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
}
