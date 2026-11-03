package dev.polybridge.app

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.core.Conversation
import dev.polybridge.core.Side
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only interfaces shared with 0.3.1 to compare both APKs on exactly the same display. */
@RunWith(AndroidJUnit4::class)
class ArchiveDensityTest {
    @Test fun countsVisibleShortBilingualTurns() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish") && InstrumentationRegistry.getArguments().getString("testDensity") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        fun nodes(): List<AccessibilityNodeInfo> {
            val root = inst.uiAutomation.rootInActiveWindow ?: return emptyList()
            val queue = ArrayDeque(listOf(root))
            return buildList {
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (node.packageName == app.packageName && node.isVisibleToUser) add(node)
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                }
            }
        }
        val fixture: (Conversation) -> Conversation = {
            (1..22).fold(Conversation()) { chat, i -> chat.typed("line-$i", Side.CHINESE, "第${i}站").translated("line-$i", "第${i}站", "Station $i.") }
        }
        app.store.changeChat(fixture)
        val until = SystemClock.elapsedRealtime() + 5000
        while (nodes().none { it.text?.toString() == "对话历史" }) {
            assertTrue(SystemClock.elapsedRealtime() < until)
            var button = nodes().firstOrNull { it.contentDescription?.toString() == "历史" || it.text?.toString() == "历史" }
            while (button != null && !button.isClickable) button = button.parent
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(200)
        }
        val count = nodes().mapNotNull { it.text?.toString() }.distinct().count { it.matches(Regex("Station [0-9]+\\.")) }
        assertTrue(count > 0)
        inst.sendStatus(0, Bundle().apply { putString("archiveVisible", "$count widthDp=${app.resources.configuration.screenWidthDp}") })
    }
}
