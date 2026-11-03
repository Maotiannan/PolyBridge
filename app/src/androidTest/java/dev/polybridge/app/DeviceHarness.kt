package dev.polybridge.app

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

internal fun openProjectLauncher(): MainActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val component = ComponentName(instrumentation.targetContext, MainActivity::class.java).flattenToShortString()
    val command = "am start -W -f 0x10008000 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component"
    val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    val deadline = SystemClock.elapsedRealtime() + 10_000
    do {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull { it.hasWindowFocus() && !it.isFinishing }
        }
        activity?.let { instrumentation.uiAutomation.waitForIdle(180, 2500); return it }
        SystemClock.sleep(50)
    } while (SystemClock.elapsedRealtime() < deadline)
    error("应用未进入前台。$output")
}
