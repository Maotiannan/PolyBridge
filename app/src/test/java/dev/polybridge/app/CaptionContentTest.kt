package dev.polybridge.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import dev.polybridge.app.ui.*
import dev.polybridge.app.audio.ListeningState
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptionContentTest {
    private fun row(id: String, lines: Int = 25, side: Side = Side.FRENCH) = TranscriptRow(
        LiveUpdate.Caption(id, side, (1..lines).joinToString("\n") { "Phrase numéro $it." },
            (1..lines).joinToString("\n") { "这是第 $it 句。" }), null)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T : View> named(root: View, name: String): T =
        descendants(root).firstOrNull { it.contentDescription == name } as? T ?: error("缺少 $name")
    private fun layout(view: View, width: Int = 600) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun content() = Robolectric.buildActivity(Activity::class.java).setup().get().let { activity ->
        CaptionContent(activity) {}.also { activity.setContentView(it) }
    }

    @Test fun aLongGrowingSentenceShowsItsTailWithoutTruncationInEitherLanguage() {
        val view = content(); var latest = row("same")
        view.render(listOf(latest)); layout(view)
        val source = named<ScrollView>(view, "滚动字幕原文")
        val target = named<ScrollView>(view, "滚动字幕译文")
        assertTrue("原文仍停在开头", source.scrollY > 0)
        assertFalse(source.canScrollVertically(1)); assertFalse(target.canScrollVertically(1))
        latest = row("same", 40); view.render(listOf(latest)); layout(view, 420)
        assertEquals(latest.line.source, (source.getChildAt(0) as TextView).text.toString())
        assertEquals(latest.line.target, (target.getChildAt(0) as TextView).text.toString())
        assertNull((source.getChildAt(0) as TextView).ellipsize)
        assertFalse(source.canScrollVertically(1)); assertFalse(target.canScrollVertically(1))
    }

    @Test fun browsingOldTextPausesFollowingAndLatestButtonResumesBothLanguages() {
        val view = content(); val original = row("first")
        view.render(listOf(original)); layout(view)
        val source = named<ScrollView>(view, "滚动字幕原文")
        assertTrue(source.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)); layout(view)
        val position = source.scrollY
        view.render(listOf(row("first", 30))); layout(view)
        assertEquals("同一句增加文字也不能打断上翻", position, source.scrollY)
        view.render(listOf(original, row("next", 35, Side.CHINESE))); layout(view)
        assertEquals(position, source.scrollY)
        assertEquals(original.line.source, (source.getChildAt(0) as TextView).text.toString())
        val latest = named<View>(view, "回到最新字幕")
        assertEquals(View.VISIBLE, latest.visibility); latest.performClick(); layout(view)
        assertFalse(source.canScrollVertically(1))
        assertEquals(row("next", 35).line.source, (source.getChildAt(0) as TextView).text.toString())
    }

    @Test fun fullHistoryShowsPerParagraphLanguageAndFollowsSameTurnGrowth() {
        val view = content()
        val rows = listOf(row("a", side = Side.CHINESE), row("b"))
        view.render(rows); layout(view)
        named<View>(view, "查看字幕全文").performClick(); layout(view)
        val scroll = named<ScrollView>(view, "滚动字幕全文")
        val text = scroll.getChildAt(0) as TextView
        assertTrue(text.text.contains("中文 → 法语")); assertTrue(text.text.contains("法语 → 中文"))
        assertTrue(scroll.scrollY > 0); assertFalse(scroll.canScrollVertically(1))
        view.render(listOf(rows[0], row("b", 50))); layout(view)
        assertTrue(text.text.contains("第 50 句")); assertFalse(scroll.canScrollVertically(1))
    }

    @Test fun aPlainTapKeepsTheNativeAccessibilityClickContract() {
        val view = content(); view.render(listOf(row("a"))); layout(view)
        val source = named<ScrollView>(view, "滚动字幕原文")
        var clicked = 0; source.setOnClickListener { clicked++ }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 20, action, 10f, 10f, 0)
            source.onTouchEvent(event); event.recycle()
        }
        assertEquals(1, clicked)
    }

    @Test fun unconfirmedQualitySurvivesHistoryAndShowsAnActionableMessage() {
        val line = LiveUpdate.Caption("bad", Side.CHINESE, "", "", issue = TranslationIssue.UNRELIABLE)
        val chat = Conversation().heard(line, LiveOrigin("capture", Kind.CAPTIONS, 0), TranslationIssue.UNRELIABLE)
        val rows = transcriptRows(chat, ListeningState(), Kind.CAPTIONS, "capture")
        assertEquals(TranslationIssue.UNRELIABLE, rows.single().line.issue)
        val view = content(); view.render(rows); layout(view)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.contains("请重新播放") })
    }
}
