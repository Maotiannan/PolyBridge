package dev.polybridge.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import dev.polybridge.app.ProbeRequest
import dev.polybridge.app.audio.LiveDesk
import dev.polybridge.core.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Owned by the media-projection service. Only the small caption card receives touches. */
internal class ScreenCaptions(context: Context, private val request: ProbeRequest, private val desk: LiveDesk,
    private val stop: () -> Unit) : AutoCloseable {
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private val window = context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val wm = window.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pos = context.getSharedPreferences("caption_window", Context.MODE_PRIVATE)
    private fun dp(n: Int) = (n * window.resources.displayMetrics.density).roundToInt()
    private fun text(size: Float, color: String) = TextView(window).apply { textSize = size; setTextColor(Color.parseColor(color)) }
    private fun TextView.showText(value: String) { if (text != value) text = value }
    private val root = LinearLayout(window).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(2), dp(12), dp(12))
        background = GradientDrawable().apply { setColor(Color.parseColor("#F319302B")); cornerRadius = dp(17).toFloat() }
        elevation = dp(4).toFloat()
    }
    private val header = text(12f, "#BAE5CD").apply { gravity = Gravity.CENTER_VERTICAL; setTypeface(typeface, Typeface.BOLD) }
    private val source = text(15f, "#DFECE7").apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val previous = text(12f, "#BAE5CD").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE }
    private val translated = text(19f, "#FFFFFF").apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD) }
    private val toggle = text(14f, "#BAE5CD").apply { gravity = Gravity.CENTER; isFocusable = true; visibility = View.GONE }
    private val fullText = text(15f, "#FFFFFF").apply { setLineSpacing(dp(2).toFloat(), 1f) }
    private val scroll = ScrollView(window).apply { addView(fullText); visibility = View.GONE }
    private var expanded = false
    private val params = WindowManager.LayoutParams(1, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        title = "PolyBridge 双语字幕"
    }
    private var attached = false
    private var heightPx = 0
    private var fraction = pos.getFloat("vertical", .19f).coerceIn(0f, 1f)

    fun show() {
        val row = LinearLayout(window).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(header, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        toggle.setPadding(dp(8), 0, dp(8), 0)
        toggle.setOnClickListener { expanded = !expanded }
        row.addView(text(14f, "#FFFFFF").apply {
            text = "停止"; gravity = Gravity.CENTER; contentDescription = "停止屏幕字幕"
            isFocusable = true; setOnClickListener { stop() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(row); root.addView(previous); root.addView(source); root.addView(translated)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)))
        header.contentDescription = "双语字幕，按住上下拖动"
        var firstY = 0f
        var originalY = 0
        header.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { firstY = event.rawY; originalY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val range = (heightPx - root.height - dp(32)).coerceAtLeast(1)
                    params.y = (originalY + (event.rawY - firstY).roundToInt()).coerceIn(0, range)
                    fraction = params.y.toFloat() / range
                    if (attached) wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> { pos.edit().putFloat("vertical", fraction).apply(); view.performClick(); true }
                else -> false
            }
        }
        resize()
        wm.addView(root, params); attached = true
        scope.launch {
            val start = SystemClock.elapsedRealtime()
            var lastWindowCheck = 0L
            var renderedChat: Conversation? = null
            var renderedDrafts: List<LiveUpdate.Caption>? = null
            var wasExpanded = false
            var incomplete: String? = null
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWindowCheck >= 500) {
                    if (!android.provider.Settings.canDrawOverlays(window)) { stop(); break }
                    resize()
                    lastWindowCheck = now
                }
                val state = desk.view.value
                val current = state.takeIf { it.id == request.id }
                val line = listOfNotNull(current?.lastLine, current?.partial).maxByOrNull { it.startedMs }
                val chat = desk.chat.value
                if (current != null && (renderedChat !== chat || renderedDrafts !== current.drafts || wasExpanded != expanded)) {
                    val rows = transcriptRows(chat, current, Kind.CAPTIONS, request.id)
                    incomplete = rows.lastOrNull()?.takeIf { it.saved?.status == TurnStatus.FAILED }?.line?.id
                    val prior = rows.getOrNull(rows.size - 2)?.line
                    previous.visibility = if (prior != null && !expanded) View.VISIBLE else View.GONE
                    if (prior != null) previous.showText("上一段  ·  " + prior.target.ifBlank { prior.source })
                    toggle.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                    toggle.showText(if (expanded) "收起" else "全文 · ${rows.size}")
                    toggle.contentDescription = if (expanded) "收起字幕全文" else "查看字幕全文"
                    scroll.visibility = if (expanded) View.VISIBLE else View.GONE
                    if (expanded) {
                        val follow = !wasExpanded || !scroll.canScrollVertically(1)
                        fullText.text = SpannableStringBuilder().apply {
                            rows.forEachIndexed { index, row ->
                                if (index > 0) append("\n\n")
                                append(row.line.source).append('\n')
                                val from = length
                                if (row.saved?.status == TurnStatus.FAILED) append("未确认 · ")
                                append(row.line.target.ifBlank { if (row.saved != null) "未收到译文" else "正在翻译…" })
                                setSpan(StyleSpan(Typeface.BOLD), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        if (follow) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                    renderedChat = chat; renderedDrafts = current.drafts; wasExpanded = expanded
                }
                header.showText(when {
                    line != null -> (if (line.side == Side.FRENCH) "法语原文  →  中文译文" else "中文原文  →  法语译文") +
                        (if (line.id == incomplete) "  ·  未确认" else "")
                    else -> "PolyBridge  ·  中法实时字幕"
                })
                source.showText(line?.source.orEmpty())
                source.visibility = if (expanded || line?.source.isNullOrBlank()) View.GONE else View.VISIBLE
                translated.visibility = if (expanded) View.GONE else View.VISIBLE
                translated.showText(when {
                    current?.reconnecting == true -> "正在重新连接…"
                    line != null -> line.target.ifBlank { if (line.id == incomplete) "未收到译文" else "正在翻译…" }
                    current?.ready != true -> "正在连接千问…"
                    now - start < 6500 -> "播放一条语音，字幕会出现在这里"
                    request.level.value > .04f -> "已收到声音，正在听…"
                    else -> "还没收到声音。请播放媒体，部分应用会限制共享。"
                })
                delay(100)
            }
        }
    }

    private fun resize() {
        val bounds = wm.currentWindowMetrics.bounds
        val contentHeight = minOf(dp(320), (bounds.height() * .42f).roundToInt())
        scroll.layoutParams?.let { if (it.height != contentHeight) { it.height = contentHeight; scroll.layoutParams = it } }
        val width = (bounds.width() - dp(24)).coerceIn(dp(120), dp(530))
        val y = ((bounds.height() - root.height - dp(32)).coerceAtLeast(1) * fraction).roundToInt()
        if (params.width == width && heightPx == bounds.height() && params.y == y) return
        heightPx = bounds.height(); params.width = width; params.y = y
        if (attached) wm.updateViewLayout(root, params)
    }

    override fun close() {
        scope.cancel()
        if (attached) { attached = false; runCatching { wm.removeViewImmediate(root) } }
    }
}
