package dev.polybridge.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView

@Composable internal fun ImmersiveTalk(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val controller = activity?.window?.insetsController
        val previous = controller?.systemBarsBehavior
        fun enter() {
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsets.Type.systemBars())
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { if (it && enabled) enter() }
        if (enabled) { enter(); view.viewTreeObserver.addOnWindowFocusChangeListener(focus) }
        onDispose {
            if (enabled) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
                controller?.show(WindowInsets.Type.systemBars())
                if (previous != null) controller.systemBarsBehavior = previous
            }
        }
    }
}
