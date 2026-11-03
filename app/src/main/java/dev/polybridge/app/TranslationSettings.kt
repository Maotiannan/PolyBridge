package dev.polybridge.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.polybridge.qwen.QwenOptions
import dev.polybridge.qwen.QwenSpeech
import dev.polybridge.qwen.Region
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Microphone(val label: String) { AUTO("系统自动选择"), PHONE("手机麦克风"), HEADSET("蓝牙耳机麦克风") }
data class TranslatorSettings(val configured: Boolean, val cloud: QwenOptions, val autoSpeak: Boolean, val opposed: Boolean,
    val mic: Microphone, val quietSpeaker: Boolean, val splitPlayback: Boolean, val privateReplay: Boolean)

class TranslationSettings(context: Context) {
    internal val headsets by lazy { dev.polybridge.app.audio.HeadsetPreferences(context.applicationContext) }
    private val prefs = context.getSharedPreferences("translator", Context.MODE_PRIVATE)
    private fun snapshot() = TranslatorSettings(prefs.contains("credential"),
        QwenOptions(runCatching { Region.valueOf(prefs.getString("region", "")!!) }.getOrDefault(Region.BEIJING),
            prefs.getString("workspace", "").orEmpty()), prefs.getBoolean("autoSpeak", true), prefs.getBoolean("opposed", true),
        runCatching { Microphone.valueOf(prefs.getString("mic", "")!!) }.getOrDefault(Microphone.AUTO),
        prefs.getBoolean("quietSpeaker", true), prefs.getBoolean("splitPlayback", true), prefs.getBoolean("privateReplay", true))
    private val values = MutableStateFlow(snapshot())
    val state = values.asStateFlow()
    data class PlaybackObservation(val type: Int, val matchedPreference: Boolean?, val gain: Float, val positionMs: Int,
        val manual: Boolean, val playbackId: Long, val ducked: Boolean = false)
    private val routes = MutableStateFlow<Map<String, PlaybackObservation>>(emptyMap())
    val lastRoutes = routes.asStateFlow()
    @Synchronized fun noteRoute(language: String?, observation: PlaybackObservation) {
        if (language != null) routes.value = routes.value + (language to observation)
    }
    @Synchronized fun privateReplay(enabled: Boolean) {
        prefs.edit().putBoolean("privateReplay", enabled).apply(); values.value = snapshot()
    }
    @Synchronized fun quietSpeaker(enabled: Boolean) {
        prefs.edit().putBoolean("quietSpeaker",enabled).apply(); values.value = snapshot()
    }
    @Synchronized fun splitPlayback(enabled: Boolean) {
        prefs.edit().putBoolean("splitPlayback",enabled).apply(); values.value = snapshot()
    }

    @Synchronized fun save(region: Region, workspace: String, newKey: String) {
        region.endpoint(workspace)
        require(newKey.isNotBlank() || prefs.contains("credential"))
        val edit = prefs.edit().putString("region", region.name).putString("workspace", workspace)
        if (newKey.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            val sealed = cipher.doFinal(newKey.trim().toByteArray(Charsets.UTF_8))
            fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
            edit.putString("credential", encode(cipher.iv) + "." + encode(sealed))
        }
        check(edit.commit())
        values.value = snapshot()
    }

    @Synchronized fun disconnect() {
        check(prefs.edit().remove("credential").commit())
        values.value = snapshot()
    }

    @Synchronized fun playback(enabled: Boolean) {
        prefs.edit().putBoolean("autoSpeak", enabled).apply()
        values.value = snapshot()
    }

    @Synchronized fun orientation(opposed: Boolean) {
        prefs.edit().putBoolean("opposed", opposed).apply()
        values.value = snapshot()
    }

    @Synchronized fun microphone(choice: Microphone) {
        prefs.edit().putString("mic", choice.name).apply()
        values.value = snapshot()
    }

    @Synchronized fun provider(): QwenSpeech? {
        val encoded = prefs.getString("credential", null) ?: return null
        val (iv, body) = encoded.split('.', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val key = String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
        return QwenSpeech(key, values.value.cloud)
    }

    private fun secret(): SecretKey {
        val alias = "polybridge.personal.translation"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}
