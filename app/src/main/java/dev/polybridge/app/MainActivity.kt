package dev.polybridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.polybridge.app.audio.*
import dev.polybridge.app.ui.*
import dev.polybridge.core.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val app get() = application as PolyBridgeApp
    private val store get() = app.store
    private val monitor by lazy { DeviceMonitor(this) }
    private var sharedAudio by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShare(intent)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent { StudioTheme { Home() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readShare(intent) }
    private fun readShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("audio/") != true) return
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        sharedAudio = uri?.takeIf { it.scheme == "content" }?.toString()
    }
    override fun onStart() { super.onStart(); monitor.start() }
    override fun onStop() {
        if (!isChangingConfigurations) store.active.value?.let {
            if (it.kind == Kind.HOLD || it.kind == Kind.LISTEN) it.end.compareAndSet(null, End.CANCELLED)
            if (it.kind == Kind.LISTEN) app.live.stopNow(it)
        }
        monitor.stop()
        super.onStop()
    }
    private fun notice(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun version(target: PlaybackTarget): String? = target.packageName?.let {
        runCatching { packageManager.getPackageInfo(it, 0).versionName }.getOrNull()
    } ?: if (target == PlaybackTarget.SELF) BuildConfig.VERSION_NAME else null

    private fun start(kind: Kind, target: PlaybackTarget, input: Int?, side: Side? = null, token: Intent? = null): String? {
        val request = store.begin(kind, target, input, side, version(target)) ?: return null
        try { ProbeService.launch(this, request, token) }
        catch (_: RuntimeException) { store.finish(request.empty(Verdict.FAILED, "FOREGROUND_START")); return null }
        return request.id
    }
    private fun rejected(kind: Kind, target: PlaybackTarget, input: Int?, verdict: Verdict, reason: String) {
        store.begin(kind, target, input, null, version(target))?.let { store.finish(it.empty(verdict, reason)) }
    }

    @Composable private fun Home() {
        val scope = rememberCoroutineScope()
        val report by store.report.collectAsState()
        val chat by store.chat.collectAsState()
        val active by store.active.collectAsState()
        val hardware by monitor.state.collectAsState()
        val settings by app.settings.state.collectAsState()
        var input by rememberSaveable { mutableStateOf<Int?>(null) }
        var output by rememberSaveable { mutableStateOf<Int?>(null) }
        var target by rememberSaveable { mutableStateOf(PlaybackTarget.SELF) }
        var pendingKind by rememberSaveable { mutableStateOf(Kind.MICROPHONE) }
        var pendingTarget by rememberSaveable { mutableStateOf(PlaybackTarget.SELF) }
        var pendingInput by rememberSaveable { mutableStateOf<Int?>(null) }
        var pendingSide by rememberSaveable { mutableStateOf<Side?>(null) }
        var toneBusy by remember { mutableStateOf(false) }
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK && it.data != null)
                start(pendingKind, pendingTarget, null, pendingSide, token = it.data)
            else { rejected(pendingKind, pendingTarget, null, Verdict.DENIED, "PROJECTION_CONSENT"); notice("本次未获播放共享授权") }
        }
        fun startConsented(kind: Kind, chosen: PlaybackTarget, selectedInput: Int?, side: Side?): String? {
            if (kind.playbackCapture) {
                if (version(chosen) == null) { rejected(kind, chosen, null, Verdict.FAILED, "TARGET_NOT_INSTALLED"); notice("手机未安装所选应用") }
                else projection.launch(projectionManager.createScreenCaptureIntent())
                return null
            }
            return start(kind, chosen, selectedInput, side)
        }
        val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                rejected(pendingKind, pendingTarget, pendingInput, Verdict.DENIED, "RECORD_AUDIO")
                notice("本次未获麦克风授权")
            } else if (pendingKind == Kind.HOLD) notice("麦克风已授权，请重新按住说话")
            else startConsented(pendingKind, pendingTarget, pendingInput, pendingSide)
        }
        fun userStart(kind: Kind, chosen: PlaybackTarget, selectedInput: Int?, side: Side? = null): String? {
            if (store.active.value != null || toneBusy) return null
            pendingKind = kind; pendingTarget = chosen; pendingInput = selectedInput; pendingSide = side
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                return startConsented(kind, chosen, selectedInput, side)
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            mic.launch(permissions.toTypedArray())
            return null
        }
        val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { monitor.refresh() }
        fun export(uri: android.net.Uri?, markdown: Boolean) {
            if (uri == null) return
            val current = store.report.value
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                        it.write(if (markdown) ReportCodec.markdown(current) else ReportCodec.json(current))
                    }
                }.isSuccess }
                notice(if (ok) "报告已导出" else "写入失败，请重新选择文件位置")
            }
        }
        val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, false) }
        val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { export(it, true) }

        fun speechStart(kind: Kind, side: Side?): String? {
            val selected = preferredMic(hardware.ports, settings.mic, app.settings.headsets)
            if (settings.mic != Microphone.AUTO && selected == null) { notice("所选麦克风未连接，请在设置中切换"); return null }
            return userStart(kind, PlaybackTarget.SELF, selected?.id, side)
        }
        Studio(app, lifecycle, chat, active, hardware, requestBluetooth = { bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            installedVersion = ::version, incomingShare = sharedAudio, consumeShare = {
            sharedAudio = null; if (intent.action == Intent.ACTION_SEND) setIntent(Intent(this, MainActivity::class.java))
        }, start = { speechStart(Kind.HOLD, it) }, listen = { speechStart(Kind.LISTEN, null) },
            captureMessage = { targetApp, direction -> userStart(Kind.MESSAGE, targetApp, null, direction) }, end = { id, cancel ->
            store.active.value?.takeIf { it.id == id }?.end?.compareAndSet(null, if (cancel) End.CANCELLED else End.RELEASED)
        }, notice = ::notice, inputLabel = settings.mic.label, inputPicker = { dismiss ->
            AlertDialog(onDismissRequest = dismiss, title = { Text("说话时的麦克风") }, text = {
                Column { Microphone.entries.forEach { choice ->
                    TextButton(onClick = { app.settings.microphone(choice); dismiss() }) {
                        Text((if (settings.mic == choice) "✓  " else "") + choice.label)
                    }
                } }
            }, confirmButton = { TextButton(onClick = dismiss) { Text("返回") } })
        }) {
                DiagnosticsPanel(hardware, report, active, input, output, target, toneBusy,
                    onInput = { input = it }, onOutput = { output = it }, onTarget = { target = it },
                    onBluetooth = { bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    onMic = { userStart(Kind.MICROPHONE, PlaybackTarget.SELF, input) },
                    onPlayback = { userStart(Kind.PLAYBACK, target, null) },
                    onStop = { active?.end?.compareAndSet(null, End.CANCELLED) },
                    onTone = {
                        if (!toneBusy && store.active.value == null) {
                            toneBusy = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        val job = currentCoroutineContext().job
                                        runCatching { AndroidTestTone(this@MainActivity).play(output) { !job.isActive } }.getOrNull()
                                    }
                                    if (result != null) store.tone(result) else notice("测试音播放失败")
                                } finally { toneBusy = false }
                            }
                        }
                    },
                    onJson = { json.launch("polybridge-report.json") }, onMarkdown = { md.launch("polybridge-report.md") })
        }
    }
}
