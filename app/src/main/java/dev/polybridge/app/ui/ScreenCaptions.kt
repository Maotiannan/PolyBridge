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
import android.widget.TextView
import dev.polybridge.app.ProbeRequest
import dev.polybridge.app.audio.LiveDesk
import dev.polybridge.core.Side
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
    private val translated = text(19f, "#FFFFFF").apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD) }
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
        row.addView(text(14f, "#FFFFFF").apply {
            text = "停止"; gravity = Gravity.CENTER; contentDescription = "停止屏幕字幕"
            isFocusable = true; setOnClickListener { stop() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(row); root.addView(source); root.addView(translated)
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
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWindowCheck >= 500) {
                    if (!android.provider.Settings.canDrawOverlays(window)) { stop(); break }
                    resize()
                    lastWindowCheck = now
                }
                val state = desk.view.value
                val current = state.takeIf { it.id == request.id }
                val line = current?.partial ?: current?.lastLine
                header.showText(when {
                    line != null -> if (line.side == Side.FRENCH) "法语原文  →  中文译文" else "中文原文  →  法语译文"
                    else -> "PolyBridge  ·  中法实时字幕"
                })
                source.showText(line?.source.orEmpty())
                source.visibility = if (line?.source.isNullOrBlank()) View.GONE else View.VISIBLE
                translated.showText(when {
                    current?.reconnecting == true -> "正在重新连接…"
                    line != null -> line.target.ifBlank { "正在翻译…" }
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
