package dev.polybridge.app.ui

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.polybridge.core.*
import kotlin.math.roundToInt

private object CaptionColors {
    val surface = Color.parseColor("#F319302B")
    val label = Color.parseColor("#BAE5CD")
    val source = Color.parseColor("#DFECE7")
    val target = Color.WHITE
}

/** Programmatic content/layout changes never count as a reader scrolling away. */
@SuppressLint("ViewConstructor") // Created with its follow-state callback, never inflated from XML.
private class FollowingScroll(context: Context, private val changed: () -> Unit) : ScrollView(context) {
    var following = true
        private set
    var maximumHeight = Int.MAX_VALUE
        set(value) { if (field != value) { field = value; requestLayout() } }
    private var downY = 0f
    private var touching = false
    private var dragged = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { isSmoothScrollingEnabled = false; isFillViewport = false }
    private fun follow(value: Boolean) { if (following != value) { following = value; changed() } }
    fun latest() { follow(true); toBottom(); requestLayout() }
    private fun toBottom() { getChildAt(0)?.let { scrollTo(0, (it.height - height + paddingTop + paddingBottom).coerceAtLeast(0)) } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = minOf(maximumHeight, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (following && !touching) toBottom()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; dragged = false; downY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.y - downY) > slop) dragged = true
                if (event.y - downY > slop && canScrollVertically(-1)) follow(false)
            }
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (event.actionMasked == MotionEvent.ACTION_UP && !dragged) performClick()
            if (dragged && !canScrollVertically(1)) follow(true)
            if (following) { toBottom(); requestLayout() }
        }
        return handled
    }
    override fun performClick(): Boolean = super.performClick()
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val direction = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (!canScrollVertically(direction)) return false
        follow(false)
        scrollBy(0, direction * height)
        if (!canScrollVertically(1)) follow(true)
        return true
    }
}

/** Text presentation, independent of the overlay window and media session. */
@SuppressLint("ViewConstructor") // Requires a stop action from the owning service.
internal class CaptionContent(context: Context, stop: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun label(size: Float, color: Int) = TextView(context).apply { textSize = size; setTextColor(color) }
    private fun button(title: String, description: String, click: () -> Unit) = Button(context).apply {
        text = title; contentDescription = description; textSize = 13f; isAllCaps = false
        setTextColor(CaptionColors.label); setPadding(dp(8), 0, dp(8), 0)
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(0x33BAE5CD), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat() })
        setOnClickListener { click() }
    }
    val header = label(12f, CaptionColors.label).apply {
        gravity = Gravity.CENTER_VERTICAL; setTypeface(typeface, Typeface.BOLD)
        contentDescription = "双语字幕，按住上下拖动"
    }
    private val toggle = button("全文", "查看字幕全文") { expanded = !expanded; redraw() }
    private val source = label(15f, CaptionColors.source)
    private val target = label(19f, CaptionColors.target).apply { setTypeface(typeface, Typeface.BOLD) }
    private val fullText = label(15f, CaptionColors.target).apply { setLineSpacing(dp(2).toFloat(), 1f) }
    private val sourceScroll = FollowingScroll(context, ::updateFollowButton).apply {
        contentDescription = "滚动字幕原文"; maximumHeight = dp(80); addView(source)
    }
    private val targetScroll = FollowingScroll(context, ::updateFollowButton).apply {
        contentDescription = "滚动字幕译文"; maximumHeight = dp(110); addView(target)
    }
    private val fullScroll = FollowingScroll(context, ::updateFollowButton).apply {
        contentDescription = "滚动字幕全文"; maximumHeight = dp(320); addView(fullText)
    }
    private val latest = button("↓ 回到最新", "回到最新字幕") {
        sourceScroll.latest(); targetScroll.latest(); fullScroll.latest(); redraw()
    }
    private var expanded = false
    private var rows: List<TranscriptRow> = emptyList()
    private var renderedRows: List<TranscriptRow>? = null
    private var status = ""
    private var shownId: String? = null

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(2), dp(12), dp(12))
        background = GradientDrawable().apply { setColor(CaptionColors.surface); cornerRadius = dp(17).toFloat() }
        elevation = dp(4).toFloat()
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LayoutParams(0, dp(48), 1f))
            addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            addView(button("停止", "停止屏幕字幕", stop), LayoutParams(dp(52), dp(48)))
        })
        addView(sourceScroll); addView(targetScroll); addView(fullScroll)
        addView(latest, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { gravity = Gravity.END })
        redraw()
    }

    fun resize(availableHeight: Int) {
        val bound = minOf(dp(320), (availableHeight * .42f).roundToInt()).coerceAtLeast(dp(80))
        fullScroll.maximumHeight = bound
        sourceScroll.maximumHeight = minOf(dp(80), bound * 2 / 5)
        targetScroll.maximumHeight = minOf(dp(110), bound * 3 / 5)
    }

    fun render(rows: List<TranscriptRow>, status: String = "") {
        if (this.rows == rows && this.status == status) return
        this.rows = rows; this.status = status; redraw()
    }

    private fun direction(row: TranscriptRow) = if (row.line.side == Side.FRENCH) "法语 → 中文" else "中文 → 法语"
    private fun translation(row: TranscriptRow): String = when {
        row.line.issue == TranslationIssue.UNRELIABLE && row.saved == null -> "识别出现异常，正在重新核对原音…"
        row.line.issue == TranslationIssue.UNRELIABLE -> "识别未确认 · 请重新播放这段语音"
        row.saved?.status == TurnStatus.FAILED -> "译文未完成 · " + row.line.target.ifBlank { "原文已保留" }
        row.saved?.status == TurnStatus.TRANSLATING -> row.line.target.ifBlank { "原文已保存 · 正在翻译…" }
        else -> row.line.target.ifBlank { "正在翻译…" }
    }
    private fun TextView.show(value: CharSequence) { if (text.toString() != value.toString()) text = value }

    private fun redraw() {
        val paused = !sourceScroll.following || !targetScroll.following
        val row = if (!expanded && paused) rows.firstOrNull { it.line.id == shownId } ?: rows.lastOrNull() else rows.lastOrNull()
        shownId = row?.line?.id
        header.show(if (expanded) "本次双语全文" else row?.let(::direction) ?: "PolyBridge · 中法字幕")
        toggle.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        toggle.text = if (expanded) "收起" else "全文 · ${rows.size}"
        toggle.contentDescription = if (expanded) "收起字幕全文" else "查看字幕全文"
        fullScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        sourceScroll.visibility = if (expanded || row?.line?.source.isNullOrBlank()) View.GONE else View.VISIBLE
        targetScroll.visibility = if (expanded) View.GONE else View.VISIBLE
        source.show(row?.line?.source.orEmpty())
        target.show(status.ifBlank { row?.let(::translation) ?: "播放一条语音，字幕会出现在这里" })
        if (expanded && renderedRows != rows) {
            fullText.text = SpannableStringBuilder().apply {
                rows.forEachIndexed { index, item ->
                    if (index > 0) append("\n\n")
                    append(direction(item)).append('\n').append(item.line.source).append('\n')
                    val from = length
                    append(translation(item))
                    setSpan(StyleSpan(Typeface.BOLD), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            renderedRows = rows
        }
        updateFollowButton()
    }

    private fun updateFollowButton() {
        latest.visibility = if (if (expanded) fullScroll.following else sourceScroll.following && targetScroll.following)
            View.GONE else View.VISIBLE
    }
}
