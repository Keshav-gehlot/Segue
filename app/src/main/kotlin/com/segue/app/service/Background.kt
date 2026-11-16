package com.segue.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeping the service alive on OEM builds that stop background apps (vivo Funtouch OS, OnePlus
 * OxygenOS among them). The battery-optimisation exemption is standard Android; the rest are
 * vendor settings that no API can change, so the app can only show the steps.
 */
object Background {
    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** System dialog asking to exempt Segue from battery optimisation (research builds only:
     *  Google Play restricts REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to some app categories). */
    @SuppressLint("BatteryLife")
    fun requestUnrestricted(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))

    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

    /** Vendor steps (menu names vary between OS versions). */
    fun vendorTips(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): List<String> =
        when (if (brand.equals("iqoo", ignoreCase = true)) "vivo" else manufacturer.lowercase()) {
        "vivo" -> listOf(
            "Settings › Battery › Background power consumption management (or High background power consumption): allow Segue.",
            "Settings › Apps › Autostart: turn Segue on.",
            "Recent apps: lock Segue so clearing recents does not close it."
        )
        "oneplus", "oppo", "realme" -> listOf(
            "App info › Battery: allow background activity / unrestricted.",
            "Settings › Battery › More settings: turn off any optimisation that stops apps in the background.",
            "Recent apps: long-press Segue and lock it."
        )
        else -> listOf("App info › Battery: set to Unrestricted.")
    }

    /** One log line describing this device's build, for the paper's device table. */
    fun describeDevice(context: Context): String {
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} name=${name ?: "-"} " +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} " +
            "patch=${Build.VERSION.SECURITY_PATCH} unrestricted=${unrestricted(context)}"
    }

    /** Human-readable label shown to the other device, e.g. "OnePlus Pad 3" rather than "OPD2415". */
    fun deviceLabel(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
