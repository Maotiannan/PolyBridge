package dev.polybridge.app

import android.content.Context
import android.content.ComponentName
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.polybridge.app.audio.availablePorts
import dev.polybridge.app.audio.ProbeService
import dev.polybridge.app.audio.HEADSET_SETUP_TIMEOUT_MS
import dev.polybridge.app.audio.label
import dev.polybridge.app.audio.route
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openLauncher(): MainActivity {
        // The ROM may refuse a background activity start by the instrumented app. Launch as the test driver.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = ComponentName(context, MainActivity::class.java).flattenToShortString()
        val command = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component"
        val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var activity: MainActivity? = null
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            activity?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("应用未进入前台。$output")
    }

    @Test fun appExposesDeviceSnapshotWithoutStartingAudio() {
        val app = context.applicationContext as PolyBridgeApp
        assertTrue(app.store.report.value.device.sdk >= 31)
        assertNotNull(availablePorts(context))
        assertNull(app.store.active.value)
        assertTrue(ReportCodec.json(app.store.report.value).contains("fingerprintHash"))
        val ports = availablePorts(context).filter { it.isSource }.joinToString { it.route().label() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("inputPorts", ports) })
    }

    @Test fun launcherStartsWithoutMicOrProjectionConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = openLauncher()
        try {
            instrumentation.waitForIdleSync()
            assertFalse(activity.isFinishing)
            assertNull((activity.application as PolyBridgeApp).store.active.value)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun tenSecondMicProbeWithExplicitOptIn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("This case needs an explicit captureMic request", args.getString("captureMic") == "true")
        assertEquals("请先在应用内授权麦克风", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = (context.applicationContext as PolyBridgeApp).store
        val input = args.getString("inputPort")?.toInt()
        val activity = openLauncher()
        var session: ProbeRequest? = null
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val request = checkNotNull(store.begin(Kind.MICROPHONE, PlaybackTarget.SELF, input, null, BuildConfig.VERSION_NAME))
                session = request
                ProbeService.launch(activity, request)
            }
            val deadline = SystemClock.elapsedRealtime() + HEADSET_SETUP_TIMEOUT_MS + 20_000
            while (store.active.value?.id == session?.id && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val report = store.report.value
            val entry = checkNotNull(report.probes.lastOrNull { it.id == session?.id }) { "采样超时" }
            val single = report.copy(probes = listOf(entry), lastTone = null)
            instrumentation.sendStatus(0, Bundle().apply { putString("probeJson", ReportCodec.json(single)) })
            assertEquals(entry.verdict.label, Verdict.SIGNAL, entry.verdict)
        } finally {
            session?.end?.compareAndSet(null, End.CANCELLED)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
