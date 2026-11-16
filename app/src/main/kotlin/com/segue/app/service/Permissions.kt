package com.segue.app.service

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime permissions, per the Nearby Connections manifest table for each Android version. */
object Permissions {
    /** Without these Nearby Connections will not advertise or discover. */
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (sdk <= 32) add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Requested together with the required ones, but Segue still starts if they are refused. */
    fun optional(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (sdk >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdk >= 37) add(ACCESS_LOCAL_NETWORK)
    }

    fun missingRequired(context: Context): List<String> =
        required().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun toRequest(context: Context): Array<String> =
        (required() + optional()).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    /** Nearby Connections no longer switches radios on by itself (Google, July 2026), so check first. */
    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
}
