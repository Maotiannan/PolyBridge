package dev.polybridge.app

import android.graphics.Rect
import android.media.*
import android.os.SystemClock
import android.os.Bundle
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeldSpeechTest {
    @Test fun heldButtonsCaptureThePhonesOwnTestSentence() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveQwen") == "true" && args.getString("captureTestSpeech") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = openProjectLauncher().application as PolyBridgeApp
        val original = app.settings.state.value
        val audio = app.getSystemService(AudioManager::class.java)
        val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val ui = instrumentation.uiAutomation
        fun button(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
            if (node.packageName == app.packageName && node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> button(child, label)?.let { return it } }
            return null
        }
        try {
            app.settings.microphone(Microphone.PHONE)
            app.settings.playback(false)
            for (side in Side.entries) {
                val label = if (side == Side.CHINESE) "中文，按住说话" else "Français, maintenir pour parler"
                val bounds = Rect()
                val player = MediaPlayer()
                var held: ProbeRequest? = null
                var down = 0L
                var pressed = false
                fun touch(action: Int) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    try { check(ui.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                try {
                    player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    app.assets.openFd("phrasebook/tickets-${side.source.substringBefore('-')}.wav").use {
                        player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    check(player.setPreferredDevice(speaker))
                    player.prepare()
                    until(6000) { ui.rootInActiveWindow?.let { button(it, label) }?.also { it.getBoundsInScreen(bounds) } != null }
                    down = SystemClock.uptimeMillis()
                    touch(MotionEvent.ACTION_DOWN)
                    pressed = true
                    until(6000) { held = app.store.active.value; held?.sampling?.value == true }
                    player.start()
                    SystemClock.sleep(130)
                    assertEquals("测试句未路由到机身扬声器，取消上传", speaker.id, player.routedDevice?.id)
                    until(8000) { !player.isPlaying }
                    SystemClock.sleep(200)
                    touch(MotionEvent.ACTION_UP)
                    pressed = false
                    val id = requireNotNull(held).id
                    until(120000) { app.store.chat.value.turns.any { it.id == id && it.status in listOf(TurnStatus.READY, TurnStatus.FAILED) } }
                    val final = app.store.chat.value.turns.first { it.id == id }
                    val probe = app.store.report.value.probes.first { it.id == id }
                    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, probe.inputRoute?.type)
                    assertEquals(final.issue?.name ?: final.verdict?.label, TurnStatus.READY, final.status)
                    instrumentation.sendStatus(0, Bundle().apply { putString("heldSpeech",
                        "${side.source} | ${final.sourceText} | ${final.translatedText} | samples=${probe.signal.samples} | mic=${probe.inputRoute?.type} | speaker=${speaker.type}") })
                    if (side == Side.CHINESE) assertTrue(final.sourceText.contains("票") && final.translatedText.contains("billet", true))
                    else assertTrue(final.sourceText.contains("billet", true) && final.translatedText.contains("票"))
                } finally {
                    if (pressed) {
                        held?.end?.compareAndSet(null, End.CANCELLED)
                        touch(MotionEvent.ACTION_CANCEL)
                    }
                    player.release()
                }
            }
        } finally { app.settings.microphone(original.mic); app.settings.playback(original.autoSpeak) }
    }

    private fun until(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "等待测试状态超时" }
            SystemClock.sleep(60)
        }
    }
}
