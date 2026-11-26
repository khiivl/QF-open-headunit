package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Chooses the unit's launcher the way its own Factory screen does (Settings > Factory > 8888 > Select Launcher).
 *
 * That screen belongs to the unit's CarSettings app, which keeps the choice in the system property
 * `persist.sys.qf.launcher` and in the QF config service (`launcher_default_app`). CarSettings also listens, for as long as it
 * runs, for the broadcast `com.qf.action.SET_LAUNCHER` carrying the package in the extra `packageName`, and answers it with
 * the same routine: it stops the old launcher, resets the theme and freeform properties, writes the property and the config
 * item, and sets the new launcher's wallpaper. Sending that broadcast needs neither root nor ADB, because the receiver is
 * registered without a permission.
 *
 * The Factory screen's own route does three things this one does not: it raises `persist.sys.new_set_launcher`, sets the UI
 * mode property, and reboots when it decides it must. So this is the lighter of the two, and the instruction to use the
 * Factory screen stays for a unit where this does not take.
 */
object QfLauncherSelect {

    private const val ACTION = "com.qf.action.SET_LAUNCHER"
    private const val EXTRA_PACKAGE = "packageName"
    private const val PROPERTY = "persist.sys.qf.launcher"
    private const val SETTLE_POLLS = 10
    private const val SETTLE_GAP_MS = 300L

    /** The launcher the unit currently has chosen, or empty when it keeps none (not a unit with this setting). */
    // CoD OS: the launcher of the unit is the ROM's to choose, not this app's: nothing is read, so nothing is ever set.
    fun current(): String = if (com.andrerinas.openheadunit.BuildConfig.CODOS) "" else readProperty(PROPERTY)

    /** Whether this unit has the launcher setting at all: its property is set, so CarSettings has a launcher to replace. */
    fun isSupported(): Boolean = current().isNotEmpty()

    /**
     * Makes [packageName] the unit's launcher and says whether the property took the new value within about three seconds.
     * The launcher it replaces is remembered once, so [restorePrevious] can put it back.
     */
    suspend fun select(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        val before = current()
        if (before.isEmpty()) {
            AppLog.i("QfLauncherSelect: this unit keeps no launcher property, nothing to set")
            return@withContext false
        }
        if (before == packageName) return@withContext true
        try {
            val settings = Settings(context)
            if (settings.qfPreviousLauncher.isEmpty()) settings.qfPreviousLauncher = before
        } catch (_: Exception) {
        }
        AppLog.i("QfLauncherSelect: asking CarSettings to make $packageName the launcher (was $before)")
        context.applicationContext.sendBroadcast(Intent(ACTION).putExtra(EXTRA_PACKAGE, packageName))
        repeat(SETTLE_POLLS) {
            delay(SETTLE_GAP_MS)
            if (current() == packageName) {
                AppLog.i("QfLauncherSelect: the launcher is now $packageName")
                return@withContext true
            }
        }
        AppLog.w("QfLauncherSelect: the launcher property is still ${current()} after the broadcast")
        false
    }

    /** Puts back the launcher that was there before [select] first changed it, if this app is the one chosen now. */
    suspend fun restorePrevious(context: Context): Boolean {
        val previous = try { Settings(context).qfPreviousLauncher } catch (_: Exception) { "" }
        if (previous.isEmpty() || current() != context.packageName) return false
        val restored = select(context, previous)
        if (restored) {
            try { Settings(context).qfPreviousLauncher = "" } catch (_: Exception) {}
        }
        return restored
    }

    private fun readProperty(key: String): String {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val value = cls.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as? String
            if (value != null) return value.trim()
        } catch (_: Throwable) {
        }
        // The reflective read can be refused on a unit that enforces the hidden-API list; getprop is open to every app.
        return try {
            Runtime.getRuntime().exec(arrayOf("getprop", key)).inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (_: Throwable) {
            ""
        }
    }
}
