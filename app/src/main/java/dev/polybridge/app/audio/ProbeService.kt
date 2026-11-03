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
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = store.active.value
        if (request == null) { stopSelf(startId); return START_NOT_STICKY }
        if (request.id != intent?.getStringExtra(ID)) return START_NOT_STICKY
        if (intent.action == STOP) { request.end.compareAndSet(null, End.CANCELLED); return START_NOT_STICKY }
        if (running != null) return START_NOT_STICKY
        running = request
        try {
            val playback = request.kind == Kind.PLAYBACK
            startForeground(100, notification(request), if (playback) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (playback) {
                @Suppress("DEPRECATION") val consent = intent.getParcelableExtra<Intent>(TOKEN)
                if (consent == null) { complete(request.empty(Verdict.DENIED, "NO_CONSENT")); return START_NOT_STICKY }
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { request.end.compareAndSet(null, End.PROJECTION_STOPPED) }
                }, Handler(Looper.getMainLooper()))
            }
            val sessionProjection = projection
            scope.launch {
                val entry = withContext(Dispatchers.IO) {
                    AndroidAudioInput(this@ProbeService).measure(request, sessionProjection) {
                        if (playback && request.target == PlaybackTarget.SELF) playControlTone(request)
                    }
                }
                complete(entry)
            }
        } catch (_: SecurityException) { complete(request.empty(Verdict.DENIED, "SERVICE_PERMISSION")) }
          catch (_: RuntimeException) { complete(request.empty(Verdict.FAILED, "SERVICE_START")) }
        return START_NOT_STICKY
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
            store.finish(it.empty(Verdict.INTERRUPTED))
        }
        projection?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(request: ProbeRequest): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "用户启动的音频测试", NotificationManager.IMPORTANCE_LOW))
        val stop = Intent(this, ProbeService::class.java).setAction(STOP).putExtra(ID, request.id)
        val action = PendingIntent.getService(this, request.id.hashCode(), stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("PolyBridge 正在采样")
            .setContentText("点“停止”可立即结束本次测试").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", action).build()).build()
    }

    companion object {
        private const val CHANNEL = "audio-probe"
        private const val ID = "session"
        private const val TOKEN = "projection"
        private const val STOP = "dev.polybridge.STOP"
        fun launch(context: Context, request: ProbeRequest, consent: Intent? = null) {
            context.startForegroundService(Intent(context, ProbeService::class.java).putExtra(ID, request.id).putExtra(TOKEN, consent))
        }
    }
}
