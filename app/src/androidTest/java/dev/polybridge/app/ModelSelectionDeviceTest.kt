package dev.polybridge.app

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.ui.*
import dev.polybridge.qwen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real dialog, isolated preferences, no network or personal-account changes. */
@RunWith(AndroidJUnit4::class)
class ModelSelectionDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::walk) } }
        inst.uiAutomation.rootInActiveWindow?.let(::walk)
        return result
    }
    private fun pick(label: String) {
        repeat(20) {
            val target = nodes().firstOrNull { it.text?.toString() == label }
            if (target != null && !target.isVisibleToUser) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                SystemClock.sleep(400)
            }
            var node = nodes().firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { SystemClock.sleep(160); return }
                node = node.parent
            }
            // OriginOS exposes scroll actions but does not reliably execute them in Compose dialogs.
            // Target the observed viewport on the active display, as for the foldable gesture tests.
            val bounds = android.graphics.Rect()
            (nodes().firstOrNull { it.isVisibleToUser && it.isScrollable } ?: inst.uiAutomation.rootInActiveWindow).getBoundsInScreen(bounds)
            val x = bounds.centerX()
            val y1 = bounds.top + bounds.height() * 3 / 4
            val y2 = bounds.top + bounds.height() / 4
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("input -d 0 swipe $x $y1 $x $y2 400"))
                .use { it.readBytes() }
            SystemClock.sleep(450)
        }
        error("Missing control: $label; visible=" + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() })
    }
    @Test fun realDialogCancelsDraftAndPersistsPresetAndFeatureOverride() {
        val activity = openProjectLauncher()
        val prefix = "qa-model-" + java.util.UUID.randomUUID()
        val ctx = object : ContextWrapper(activity) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = activity.getSharedPreferences(prefix + name, mode)
        }
        ctx.getSharedPreferences("translator", 0).edit().putString("credential", "fixture-not-real").commit()
        val settings = TranslationSettings(ctx)
        var generation = 0
        fun show() {
            val opened = ++generation
            inst.runOnMainSync { activity.setContent { StudioTheme {
                key(opened) {
                var open by remember { mutableStateOf(true) }
                if (open) CloudDialog(settings, { open = false }, { open = false })
                }
            } } }
            inst.waitForIdleSync(); SystemClock.sleep(300)
        }
        try {
            show(); pick("Audio 3.1"); pick("返回")
            assertEquals(ModelPreset.CURRENT, TranslationSettings(ctx).state.value.models.preset)
            show(); pick("Audio 3.1"); pick("文字翻译与重译"); pick("MT Plus"); pick("保存")
            val saved = TranslationSettings(ctx).state.value.models
            assertEquals(ModelPreset.AUDIO31, saved.preset)
            assertEquals(ModelIds.MT_PLUS, saved.model(ModelFeature.TEXT))
            assertEquals(ModelIds.AUDIO31, saved.model(ModelFeature.CAPTIONS))
            show(); pick("当前方案"); pick("保存")
            assertEquals(ModelSelection(), TranslationSettings(ctx).state.value.models)
        } finally {
            activity.deleteSharedPreferences(prefix + "translator")
            inst.runOnMainSync { activity.recreate() }
        }
    }
}
