package dev.polybridge.app.audio

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal class TranslatedAudioShare(private val context: Context) {
    private val folder = File(context.cacheDir, "translated-speech")
    fun clean() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        folder.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
    fun create(bytes: ByteArray): Intent {
        check(folder.isDirectory || folder.mkdirs())
        clean()
        val file = File.createTempFile("voice-", ".wav", folder)
        try {
            file.outputStream().use { it.write(bytes) }
            return outgoing(FileProvider.getUriForFile(context, context.packageName + ".speechfiles", file))
        }
        catch (failure: Exception) { file.delete(); throw failure }
    }

    fun outgoing(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("PolyBridge", uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
