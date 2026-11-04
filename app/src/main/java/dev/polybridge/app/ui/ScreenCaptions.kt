package dev.polybridge.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import dev.polybridge.app.ProbeRequest
import dev.polybridge.app.audio.LiveDesk
import dev.polybridge.core.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Owns only the overlay window, position and session observation. */
internal class ScreenCaptions(context: Context, private val request: ProbeRequest, private val desk: LiveDesk,
    private val stop: () -> Unit) : AutoCloseable {
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private val window = context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val wm = window.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pos = context.getSharedPreferences("caption_window", Context.MODE_PRIVATE)
    private fun dp(n: Int) = (n * window.resources.displayMetrics.density).roundToInt()
    private val root = CaptionContent(window, stop)
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
        var firstY = 0f
        var originalY = 0
        root.header.setOnTouchListener { view, event ->
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
            var rows = emptyList<TranscriptRow>()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWindowCheck >= 500) {
                    if (!android.provider.Settings.canDrawOverlays(window)) { stop(); break }
                    resize()
                    lastWindowCheck = now
                }
                val current = desk.view.value.takeIf { it.id == request.id }
                val chat = desk.chat.value
                if (current != null && (renderedChat !== chat || renderedDrafts !== current.drafts)) {
                    rows = transcriptRows(chat, current, Kind.CAPTIONS, request.id)
                    renderedChat = chat; renderedDrafts = current.drafts
                }
                root.render(rows, when {
                    current?.reconnecting == true -> "正在重新连接…"
                    rows.isNotEmpty() -> ""
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
        root.resize(bounds.height())
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
