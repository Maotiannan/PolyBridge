package dev.polybridge.app.ime

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.polybridge.app.*
import dev.polybridge.app.audio.*
import dev.polybridge.core.*
import kotlinx.coroutines.*

internal enum class InsertResult { INSERTED, SELECTION_CHANGED, UNAVAILABLE }

internal fun insertCandidate(connection: InputConnection, expectedSelection: String?, text: String): InsertResult {
    if (text.isBlank()) return InsertResult.UNAVAILABLE
    if (expectedSelection != null && connection.getSelectedText(0)?.toString() != expectedSelection)
        return InsertResult.SELECTION_CHANGED
    connection.beginBatchEdit()
    val inserted = try { connection.commitText(text, 1) } finally { connection.endBatchEdit() }
    return if (inserted) InsertResult.INSERTED else InsertResult.UNAVAILABLE
}

internal fun isPrivateEditor(type: Int): Boolean {
    val variation = type and InputType.TYPE_MASK_VARIATION
    return when (type and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> type == InputType.TYPE_NULL
    }
}

class TranslationKeyboard : InputMethodService() {
    private val app get() = application as PolyBridgeApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ink = Color.rgb(24, 44, 39)
    private val forest = Color.rgb(25, 63, 54)
    private val paper = Color.rgb(247, 246, 240)
    private val mint = Color.rgb(229, 237, 223)
    private lateinit var status: TextView
    private lateinit var candidate: EditText
    private lateinit var keys: LinearLayout
    private lateinit var direction: Button
    private lateinit var mic: Button
    private lateinit var insert: Button
    private var side = Side.CHINESE
    private var heldId: String? = null
    private var watching: String? = null
    private var selection: String? = null
    private var sensitive = true
    private var uppercase = false

    override fun onCreate() {
        super.onCreate()
        scope.launch { app.store.chat.collect { chat ->
            val id = watching ?: return@collect
            val turn = chat.turns.find { it.id == id } ?: return@collect
            if (!::status.isInitialized || sensitive) return@collect
            when (turn.status) {
                TurnStatus.READY -> { watching = null; showCandidate(turn.translatedText) }
                TurnStatus.FAILED -> { watching = null; status.text = "这句未完成，请重试或在应用中查看原文。" }
                TurnStatus.TRANSCRIBING -> status.text = "正在识别原文…"
                TurnStatus.TRANSLATING -> status.text = "正在翻译…"
                else -> Unit
            }
        } }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper); setPadding(dp(8),dp(5),dp(8),dp(4)) }
        val bar = row(root)
        bar.addView(TextView(this).apply { text = "PolyBridge"; setTextColor(forest); textSize = 18f
            setTypeface(typeface, Typeface.BOLD) }, weight())
        direction = button(bar, "中 → 法") {
            if (heldId == null) { side = if (side == Side.CHINESE) Side.FRENCH else Side.CHINESE; labels() }
        }
        button(bar, "换键盘") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        status = TextView(this).apply { setTextColor(ink); textSize = 14f; setPadding(dp(5),dp(3),dp(5),dp(3)) }
        root.addView(status)
        candidate = EditText(this).apply {
            showSoftInputOnFocus = false; hint = "译文会显示在这里"; textSize = 17f; setTextColor(ink)
            setSingleLine(false); maxLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(candidate)
        val controls = row(root)
        mic = button(controls, "按住说中文", main = true) {
            if (heldId == null) beginVoice() else endVoice(false)
        }
        mic.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { beginVoice(); true }
                MotionEvent.ACTION_MOVE -> {
                    if (event.y < -dp(14) || event.x < -dp(14) || event.x > mic.width + dp(14) || event.y > mic.height + dp(14)) endVoice(true)
                    true
                }
                MotionEvent.ACTION_UP -> { endVoice(false); true }
                MotionEvent.ACTION_CANCEL -> { endVoice(true); true }
                else -> false
            }
        }
        button(controls, "翻译所选") { translateSelection() }
        button(controls, "最近译文") {
            if (!sensitive && heldId == null) {
                watching = null; selection = null
                app.store.chat.value.turns.lastOrNull { it.status == TurnStatus.READY }?.let { showCandidate(it.translatedText) }
                    ?: run { status.text = "本机还没有已完成的译文。" }
            }
        }
        val edit = row(root)
        button(edit, "校对") {
            if (!sensitive && candidate.text.isNotBlank()) {
                keys.visibility = if (keys.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (keys.visibility == View.VISIBLE) { candidate.requestFocus(); candidate.setSelection(candidate.length()) }
            }
        }
        insert = button(edit, "插入译文", main = true) { commitCandidate() }
        keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(keys)
        makeKeys()
        resetView()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearSession()
        sensitive = attribute == null || isPrivateEditor(attribute.inputType)
        if (::keys.isInitialized) resetView()
    }
    override fun onFinishInputView(finishingInput: Boolean) { clearSession(); super.onFinishInputView(finishingInput) }
    override fun onFinishInput() { clearSession(); super.onFinishInput() }
    override fun onDestroy() { clearSession(); scope.cancel(); super.onDestroy() }
    override fun onEvaluateFullscreenMode() = false

    private fun clearSession() { endVoice(true); watching = null; selection = null }
    private fun resetView() {
        candidate.setText(""); candidate.isEnabled = !sensitive; keys.visibility = View.GONE
        mic.isEnabled = !sensitive; insert.isEnabled = !sensitive
        status.text = if (sensitive) "私密输入框 · 翻译已暂停" else "按住说话，或先在输入框中选中一段文字。"
        labels()
    }
    private fun labels() {
        direction.text = if (side == Side.CHINESE) "中 → 法" else "法 → 中"
        mic.text = if (heldId != null) "松开翻译" else if (side == Side.CHINESE) "按住说中文" else "按住说法语"
    }

    private fun beginVoice() {
        if (sensitive || heldId != null) return
        if (!app.settings.state.value.configured || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "请先打开 PolyBridge 配置千问、允许麦克风。"; return
        }
        val choice = app.settings.state.value.mic
        val port = preferredMic(availablePorts(this), choice, app.settings.headsets)
        if (choice != Microphone.AUTO && port == null) { status.text = "所选麦克风未连接。"; return }
        val request = app.store.begin(Kind.HOLD, PlaybackTarget.SELF, port?.id, side, BuildConfig.VERSION_NAME)
        if (request == null) { status.text = "麦克风正在使用。"; return }
        selection = null; watching = request.id; heldId = request.id
        try { ProbeService.launch(this, request); candidate.setText(""); status.text = "正在收音，滑出可取消。"; labels() }
        catch (_: RuntimeException) {
            app.store.finish(request.empty(Verdict.FAILED, "FOREGROUND_START")); heldId = null; watching = null
            status.text = "系统未允许键盘收音，可在应用内说话后插入最近译文。"; labels()
        }
    }

    private fun endVoice(cancel: Boolean) {
        val id = heldId ?: return
        app.store.active.value?.takeIf { it.id == id }?.end?.compareAndSet(null, if (cancel) End.CANCELLED else End.RELEASED)
        heldId = null
        if (cancel) { watching = null; if (::status.isInitialized) status.text = "已取消。" }
        if (::mic.isInitialized) labels()
    }

    private fun translateSelection() {
        if (sensitive || heldId != null) return
        val source = currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        if (source.isBlank() || source.length > 4000) { status.text = "请先在输入框选中 1 至 4000 字。"; return }
        if (!app.settings.state.value.configured) { status.text = "请先到 PolyBridge 设置连接千问。"; return }
        selection = source; candidate.setText(""); watching = app.translator.typed(side, source); status.text = "正在翻译…"
    }

    private fun showCandidate(text: String) {
        candidate.setText(text); candidate.setSelection(candidate.length()); status.text = "可先校对，再插入输入框。"
    }
    private fun commitCandidate() {
        if (sensitive || candidate.text.isBlank()) return
        val conn = currentInputConnection ?: return
        when (insertCandidate(conn, selection, candidate.text.toString())) {
            InsertResult.SELECTION_CHANGED -> status.text = "选区已变化，请重新选择。"
            InsertResult.UNAVAILABLE -> status.text = "当前输入框未能插入译文。"
            InsertResult.INSERTED -> {
                selection = null; candidate.setText(""); keys.visibility = View.GONE; status.text = "已插入，可以继续编辑。"
            }
        }
    }

    private fun makeKeys() {
        keys.removeAllViews()
        for (line in listOf("azertyuiop", "qsdfghjklm", "wxcvbn'éèà", "çêûô.,?!-")) {
            val strip = row(keys)
            for (c in line) {
                val letter = if (uppercase) c.uppercase() else c.toString()
                button(strip, letter, small = true) { replace(letter) }
            }
        }
        val last = row(keys)
        button(last, "⇧", small = true) { uppercase = !uppercase; makeKeys() }
        button(last, "空格", small = true) { replace(" ") }
        button(last, "退格", small = true) { replace("", true) }
        button(last, "收起", small = true) { keys.visibility = View.GONE }
    }
    private fun replace(text: String, backspace: Boolean = false) {
        val a = candidate.selectionStart.coerceAtLeast(0); val b = candidate.selectionEnd.coerceAtLeast(0)
        var from = minOf(a,b)
        if (backspace && a == b && from > 0) from = Character.offsetByCodePoints(candidate.text, from, -1)
        candidate.text.replace(from, maxOf(a,b), text)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun row(parent: LinearLayout) = LinearLayout(this).also { it.gravity = Gravity.CENTER_VERTICAL; parent.addView(it) }
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun button(parent: LinearLayout, label: String, main: Boolean = false, small: Boolean = false, click: () -> Unit): Button {
        val b = Button(this).apply {
            text = label; textSize = if (small) 14f else 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0
            minHeight = dp(if (small) 36 else 44); minimumHeight = minHeight; setPadding(dp(2),0,dp(2),0)
            setTextColor(if (main) paper else ink)
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(if (main) forest else mint) }
            setOnClickListener { click() }
        }
        parent.addView(b, weight().apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
        return b
    }
}
