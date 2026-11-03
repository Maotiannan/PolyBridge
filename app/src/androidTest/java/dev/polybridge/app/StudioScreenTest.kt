package dev.polybridge.app

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioScreenTest {
    private val test get() = InstrumentationRegistry.getInstrumentation()

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = test.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.packageName == "dev.polybridge.app" && match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun waitFor(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < until) {
            find(match)?.let { return it }
            SystemClock.sleep(100)
        }
        error("应用没有显示预期控件")
    }

    private fun pick(label: String) {
        var node: AccessibilityNodeInfo? = waitFor { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        error("不可点击的控件 $label")
    }

    @Test fun phrasebookHistoryAndSettingsAreAccessibleFromTheLauncher() {
        openProjectLauncher()
        pick("常用表达")
        pick("出行")
        waitFor { it.text?.toString() == "请问，火车站在哪里？" }
        pick("历史")
        waitFor { it.text?.toString() == "对话历史" }
        pick("设置")
        waitFor { it.text?.toString() == "翻译服务" }
        repeat(3) {
            if (find { it.text?.toString() == "设备诊断" && it.isVisibleToUser } == null) {
                find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(200)
            }
        }
        pick("设备诊断")
        waitFor { it.text?.toString() == "麦克风" }
    }

    @Test fun liveCallsAndVoiceToolsOpenWithoutStartingARecording() {
        val app = openProjectLauncher().application as PolyBridgeApp
        pick("自由监听")
        waitFor { it.text?.toString() == "开始监听" }
        assertNull(app.store.active.value)
        test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        pick("中文转法语")
        waitFor { it.text?.toString() == "按住说中文，松手出法语" }
        pick("去看屏幕字幕")
        waitFor { it.text?.toString() == "从应用播放中采集" }
        assertNull(find { it.text?.toString() in listOf("选择语音", "切换键盘", "Français → 中文") })
        assertNull(app.store.active.value)
    }

    @Test fun sendsTheExactFrenchTextFromTheKeyboardDialog() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveQwen") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        val original = "Le train part demain matin."
        val before = app.store.chat.value.turns.map(Turn::id).toSet()
        pick("打字")
        waitFor { it.text?.toString() == "打字翻译" }
        pick("Français → 中文")
        waitFor { it.text?.toString()?.contains("原文 · Français") == true }
        val field = waitFor { it.isEditable && !it.isPassword }
        val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, original) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
        pick("翻译")
        val until = SystemClock.elapsedRealtime() + 60000
        var result: Turn? = null
        while (SystemClock.elapsedRealtime() < until) {
            result = app.store.chat.value.turns.find { it.id !in before && it.sourceText == original }
            if (result?.status in listOf(TurnStatus.FAILED, TurnStatus.READY)) break
            SystemClock.sleep(150)
        }
        assertEquals(result?.issue?.name, TurnStatus.READY, result?.status)
        assertEquals(Side.FRENCH, result?.side)
        assertTrue(result!!.translatedText.contains("明") && (result.translatedText.contains("车") || result.translatedText.contains("列")))
        waitFor { it.text?.toString() == result.translatedText }
        test.sendStatus(0, Bundle().apply { putString("displayed", result.sourceText + " | " + result.translatedText) })
    }
}
