package com.andrerinas.openheadunit.connection

import android.content.Context
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Remembers that the user pressed Cancel, so nothing connects on its own until they ask for a connection.
 *
 * Held across restarts of the service and of the app, and across the unit's sleep: each of those used to start the
 * attempts again, switching the hotspot on and taking the Wi-Fi radio back from whatever the user had put it to. A
 * reboot ends it. The record has a file of its own, so that a reset or a restored backup leaves it alone.
 */
object UserCancelHold {

    private const val FILE = "user-cancel-hold"
    private const val KEY_BOOT = "cancelled-in-boot"

    fun set(context: Context) {
        try {
            prefs(context).edit().putInt(KEY_BOOT, bootCount(context)).apply()
        } catch (e: Exception) {
            AppLog.w("UserCancelHold: could not store the cancel: ${e.message}")
        }
    }

    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_BOOT).apply()
        } catch (e: Exception) {
            AppLog.w("UserCancelHold: could not clear the cancel: ${e.message}")
        }
    }

    /** False when the record cannot be read, as before the first unlock. */
    fun isHeld(context: Context): Boolean = try {
        val prefs = prefs(context)
        prefs.contains(KEY_BOOT) && prefs.getInt(KEY_BOOT, 0) == bootCount(context)
    } catch (e: Exception) {
        false
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Which boot this is. Below Android 7 it is not counted, and the cancel then lasts until the next ask. */
    private fun bootCount(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
        } else {
            0
        }
}
