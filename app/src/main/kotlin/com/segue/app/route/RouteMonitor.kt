package com.segue.app.route

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Headset-route readiness (handover FR-03): "would this phone's media audio go to the Bluetooth
 * headset right now?" Bond state alone is not enough, and with multipoint earbuds both phones are
 * connected at once, so on Android 13+ we ask which device media would actually be routed to.
 * This is evidence, not proof of audibility: the earbuds still choose which source they play.
 */
class RouteMonitor(context: Context, private val onChange: () -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    @Volatile private var lastReady = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
    }

    fun start() {
        am.registerAudioDeviceCallback(callback, main)
        lastReady = isReady()
    }

    fun stop() = am.unregisterAudioDeviceCallback(callback)

    /** Computed on demand: the coordinator asks at PREPARE time, so it never uses a stale answer. */
    fun isReady(): Boolean = routedHeadsets().isNotEmpty()

    fun describe(): String {
        val routed = routedHeadsets()
        if (routed.isNotEmpty()) return routed.first().productName?.toString()?.ifBlank { null } ?: "Bluetooth headset"
        val connected = connectedHeadsets()
        return if (connected.isNotEmpty()) "Headset connected but not the media output" else "No Bluetooth headset"
    }

    private fun connectedHeadsets(): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in HEADSET_TYPES }

    private fun routedHeadsets(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 33) am.getAudioDevicesForAttributes(mediaAttrs).filter { it.type in HEADSET_TYPES }
        else connectedHeadsets()

    private fun changed() {
        val now = isReady()
        if (now != lastReady) { lastReady = now }
        onChange()
    }

    companion object {
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
    }
}
