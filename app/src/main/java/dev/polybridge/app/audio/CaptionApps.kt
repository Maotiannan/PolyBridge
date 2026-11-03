package dev.polybridge.app.audio

import android.content.Context
import android.content.Intent
import dev.polybridge.core.PlaybackTarget

internal data class CaptionApp(val pkg: String, val name: String)

internal fun captionApps(context: Context): List<CaptionApp> {
    val pm = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val priority = listOf(PlaybackTarget.WECHAT.packageName, PlaybackTarget.WHATSAPP.packageName, PlaybackTarget.WHATSAPP_BUSINESS.packageName)
    return pm.queryIntentActivities(query, 0).map { CaptionApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filterNot { it.pkg == context.packageName }.distinctBy { it.pkg }
        .sortedWith(compareBy<CaptionApp> { priority.indexOf(it.pkg).let { index -> if (index < 0) Int.MAX_VALUE else index } }.thenBy { it.name })
}
