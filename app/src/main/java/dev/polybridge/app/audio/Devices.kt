package dev.polybridge.app.audio

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.polybridge.core.Route
import kotlinx.coroutines.flow.MutableStateFlow

fun availablePorts(context: Context): List<AudioDeviceInfo> = context.getSystemService(AudioManager::class.java)
    .getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).toList()

fun AudioDeviceInfo.route() = Route(type, id)

fun typeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "手机听筒"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话耳机"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙媒体耳机"
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "低功耗蓝牙音频"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频"
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "系统播放捕获"
    AudioDeviceInfo.TYPE_TELEPHONY -> "电话音频"
    else -> "其他音频设备（$type）"
}
fun Route?.label(): String = this?.let { "${typeLabel(it.type)} #${it.portId}" } ?: "未观测"

data class Headset(val name: String, val connected: Boolean?)
data class Hardware(val ports: List<AudioDeviceInfo>, val paired: List<Headset>, val hasBluetoothAccess: Boolean)

class DeviceMonitor(private val context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val profiles = mutableMapOf<Int, BluetoothProfile>()
    private val requested = mutableSetOf<Int>()
    private var open = false
    val state = MutableStateFlow(Hardware(emptyList(), emptyList(), false))
    private val changes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = refresh()
    }
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!open) { adapter?.closeProfileProxy(profile, proxy); return }
            profiles[profile] = proxy
            refresh()
        }
        override fun onServiceDisconnected(profile: Int) { profiles.remove(profile); refresh() }
    }
    fun start() {
        open = true
        manager.registerAudioDeviceCallback(changes, Handler(Looper.getMainLooper()))
        refresh()
    }
    fun refresh() {
        if (!open) return
        val allowed = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val endpoints = availablePorts(context)
        val paired = if (allowed) runCatching {
            val ids = mutableListOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
            if (Build.VERSION.SDK_INT >= 33) ids += BluetoothProfile.LE_AUDIO
            ids.filter { requested.add(it) }.forEach {
                if (adapter?.getProfileProxy(context, listener, it) != true) requested.remove(it)
            }
            val connected = profiles.values.flatMap { it.connectedDevices }.toSet()
            adapter?.bondedDevices.orEmpty().map { device ->
                Headset(device.name ?: "已配对设备", when {
                    device in connected -> true
                    profiles.keys.containsAll(ids) -> false
                    else -> null
                })
            }
        }.getOrDefault(emptyList()) else emptyList()
        state.value = Hardware(endpoints, paired, allowed)
    }
    fun stop() {
        open = false
        manager.unregisterAudioDeviceCallback(changes)
        profiles.forEach { (id, proxy) -> adapter?.closeProfileProxy(id, proxy) }
        profiles.clear()
        requested.clear()
    }
}
