package dev.polybridge.app.audio

import android.content.Context
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class HeadsetRole(val title: String) {
    MICROPHONE("说话用的麦克风"), CHINESE("接收中文译音"), FRENCH("接收法语译音")
}

internal class HeadsetPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("audio_roles", Context.MODE_PRIVATE)
    private val salt = prefs.getString("salt", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("salt", it).commit())
    }
    private fun load() = HeadsetRole.entries.mapNotNull { role -> prefs.getString(role.name, null)?.let { role to it } }.toMap()
    private val roles = MutableStateFlow(load())
    val assignments = roles.asStateFlow()

    fun identity(address: String?): String? {
        val value = address?.trim()?.uppercase() ?: return null
        if (!MAC.matches(value) || value == "02:00:00:00:00:00" || value == "00:00:00:00:00:00") return null
        return MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun assign(role: HeadsetRole, device: String?) {
        require(device == null || device.matches(Regex("[0-9a-f]{64}")))
        check(prefs.edit().putString(role.name, device).commit())
        roles.value = load()
    }

    fun matches(role: HeadsetRole, address: String?): Boolean {
        val desired = assignments.value[role] ?: return true
        return desired == identity(address)
    }

    private companion object { val MAC = Regex("(?:[A-F0-9]{2}:){5}[A-F0-9]{2}") }
}
