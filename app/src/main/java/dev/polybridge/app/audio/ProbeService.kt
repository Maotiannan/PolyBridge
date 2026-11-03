package dev.polybridge.app.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.polybridge.app.*
import dev.polybridge.core.*
import kotlinx.coroutines.*

class ProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = (application as PolyBridgeApp).store
    private var running: ProbeRequest? = null
    private var projection: MediaProjection? = null
    private var captions: dev.polybridge.app.ui.ScreenCaptions? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = store.active.value
        if (request == null) { stopSelf(startId); return START_NOT_STICKY }
        if (request.id != intent?.getStringExtra(ID)) return START_NOT_STICKY
        if (intent.action == STOP) {
            stopSession(request)
            return START_NOT_STICKY
        }
        if (intent.action == FINISH_MESSAGE) {
            if (request.kind == Kind.MESSAGE) request.end.compareAndSet(null, End.RELEASED)
            return START_NOT_STICKY
        }
        if (running != null) return START_NOT_STICKY
        running = request
        try {
            val playback = request.kind.playbackCapture
            startForeground(100, notification(request), if (playback) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (playback) {
                @Suppress("DEPRECATION") val consent = intent.getParcelableExtra<Intent>(TOKEN)
                if (consent == null) { complete(request.empty(Verdict.DENIED, "NO_CONSENT")); return START_NOT_STICKY }
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (running?.id == request.id && request.end.compareAndSet(null, End.PROJECTION_STOPPED) && request.kind == Kind.CAPTIONS) {
                            (application as PolyBridgeApp).live.stopNow(request)
                            captions?.close()
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            }
            val sessionProjection = projection
            if (request.kind == Kind.CAPTIONS) {
                if (!android.provider.Settings.canDrawOverlays(this)) { complete(request.empty(Verdict.DENIED, "OVERLAY_PERMISSION")); return START_NOT_STICKY }
                captions = dev.polybridge.app.ui.ScreenCaptions(this, request, (application as PolyBridgeApp).live) { stopSession(request) }
                captions?.show()
            }
            scope.launch {
                var wave: ByteArray? = null
                val entry = withContext(Dispatchers.IO) {
                    if (request.kind.streaming) (application as PolyBridgeApp).live.record(request, sessionProjection)
                    else AndroidAudioInput(this@ProbeService) { wave = it }.measure(request, sessionProjection) {
                        request.sampling.value = true
                        if (request.kind == Kind.PLAYBACK && request.target == PlaybackTarget.SELF) playControlTone(request)
                    }
                }
                complete(entry)
                if (request.side != null) (application as PolyBridgeApp).translator.recorded(request.id, wave)
            }
        } catch (_: SecurityException) { complete(request.empty(Verdict.DENIED, "SERVICE_PERMISSION")) }
          catch (_: RuntimeException) { complete(request.empty(Verdict.FAILED, "SERVICE_START")) }
        return START_NOT_STICKY
    }

    private fun stopSession(request: ProbeRequest) {
        if (request.kind == Kind.CAPTIONS) {
            captions?.close()
            if (request.sampling.value) { request.end.compareAndSet(null, End.RELEASED); return }
        }
        request.end.compareAndSet(null, End.CANCELLED)
        if (request.kind.streaming) (application as PolyBridgeApp).live.stopNow(request)
    }

    private fun playControlTone(request: ProbeRequest) {
        scope.launch {
            delay(350)
            val result = withContext(Dispatchers.IO) {
                runCatching { AndroidTestTone(this@ProbeService).play(null) { request.end.get() != null } }.getOrNull()
            }
            if (result != null) store.tone(result)
        }
    }
    private fun complete(entry: ProbeEntry) {
        captions?.close(); captions = null
        store.finish(entry)
        running = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        running?.let {
            it.end.compareAndSet(null, End.INTERRUPTED)
            if (it.kind.streaming) (application as PolyBridgeApp).live.stopNow(it)
            store.finish(it.empty(Verdict.INTERRUPTED))
        }
        captions?.close(); captions = null
        projection?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(request: ProbeRequest): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "收音与翻译", NotificationManager.IMPORTANCE_LOW))
        val stop = Intent(this, ProbeService::class.java).setAction(STOP).putExtra(ID, request.id)
        val action = PendingIntent.getService(this, request.id.hashCode(), stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val conversation = request.kind == Kind.HOLD
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(when { request.kind == Kind.CAPTIONS -> "屏幕字幕 · 中法自动互译"; request.kind == Kind.MESSAGE -> "翻译语音消息 · ${request.target.label}"; request.kind == Kind.LISTEN -> "自由监听 · 中法翻译"; conversation -> "面对面 · 正在收音"; else -> "PolyBridge 正在采样" })
            .setContentText(when { request.kind == Kind.CAPTIONS -> "正在听所选应用允许共享的媒体声音，点停止即可结束"; request.kind == Kind.MESSAGE -> "播放语音后可直接在此结束并翻译"; request.kind == Kind.LISTEN -> "点停止结束这次监听"; conversation -> "松开发送，或点停止取消本句"; else -> "点“停止”可立即结束本次测试" }).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        if (request.kind == Kind.MESSAGE) {
            val finish = Intent(this, ProbeService::class.java).setAction(FINISH_MESSAGE).putExtra(ID, request.id)
            val pending = PendingIntent.getService(this, request.id.hashCode(), finish, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "结束并翻译", pending).build())
        }
        return builder.addAction(Notification.Action.Builder(null, if (request.kind == Kind.MESSAGE) "取消" else "停止", action).build()).build()
    }

    companion object {
        private const val CHANNEL = "audio-probe"
        private const val ID = "session"
        private const val TOKEN = "projection"
        private const val STOP = "dev.polybridge.STOP"
        private const val FINISH_MESSAGE = "dev.polybridge.FINISH_MESSAGE"
        fun stop(context: Context, id: String) {
            context.startService(Intent(context, ProbeService::class.java).setAction(STOP).putExtra(ID, id))
        }
        fun launch(context: Context, request: ProbeRequest, consent: Intent? = null) {
            context.startForegroundService(Intent(context, ProbeService::class.java).putExtra(ID, request.id).putExtra(TOKEN, consent))
        }
    }
}
