package com.andrerinas.openheadunit.utils

import android.content.Context
import com.andrerinas.openheadunit.BuildConfig

/**
 * Sets the unit's hotspot name and password through the local adb bridge: OHU's own adb backend runs
 * [com.andrerinas.openheadunit.tools.HotspotConfigTool] from the installed APK as the system user, which is what
 * Android's hotspot API asks for. The hotspot follows the new values by itself within seconds; phones joined to it
 * drop and rejoin.
 */
object HotspotConfigClient {

    /** What may go into the command line unquoted: no quotes, backslash, dollar or backtick. */
    private val SAFE = Regex("^[A-Za-z0-9 _.+=@#%^*()!?,:;/-]+$")

    /** Null when the name and password can be set, otherwise why not. */
    fun problem(name: String, password: String): String? = when {
        name.isEmpty() || name.length > 32 -> "The name must be 1 to 32 characters."
        password.length !in 8..63 -> "The password must be 8 to 63 characters."
        !SAFE.matches(name) || !SAFE.matches(password) ->
            "Use letters, digits, spaces and . _ + = @ # % ^ * ( ) ! ? , : ; / - only."
        else -> null
    }

    /**
     * Runs [tool] arguments through app_process from the installed APK as the system user. Upstream gets there with
     * `su 1000`; CoD OS has no su, and the app already runs as uid 1000 there, so the `su` hop is left out.
     */
    private fun asSystemUser(packageName: String, toolArgs: String, codos: Boolean): String {
        val run = "CLASSPATH=\$APK app_process /system/bin com.andrerinas.openheadunit.tools.HotspotConfigTool $toolArgs"
        return "APK=\$(pm path $packageName | head -1 | cut -d: -f2); " +
            if (codos) run else "su 1000 sh -c \"$run\""
    }

    /** The command for the adb shell; the caller has checked [problem]. */
    fun buildSetCommand(packageName: String, name: String, password: String, codos: Boolean = BuildConfig.CODOS): String =
        asSystemUser(packageName, "set '$name' '$password'", codos)

    /** The command that puts the hotspot back on 5 GHz if it is on anything else. */
    fun buildEnsure5GhzCommand(packageName: String, codos: Boolean = BuildConfig.CODOS): String =
        asSystemUser(packageName, "ensure-5ghz", codos)

    /**
     * Makes sure the unit's hotspot is on 5 GHz. Cheap when it already is: the hostapd configuration, where there is
     * one, says so without starting anything.
     */
    suspend fun ensure5Ghz(context: Context): Pair<Boolean, String> {
        val (readCode, text) = OemAppManager.executeCommand(context, HostapdConfig.READ_COMMAND)
        if (readCode == 0 && HostapdConfig.runs5Ghz(text) == true) return Pair(true, "already 5 GHz")
        val (code, out) = OemAppManager.executeCommand(context, buildEnsure5GhzCommand(context.packageName))
        val ok = code == 0 && (out.contains("already 5 GHz") || out.contains("set 5 GHz"))
        AppLog.i("hotspot: making sure it is on 5 GHz finished with code=$code ok=$ok: ${out.trim()}")
        return Pair(ok, out.trim())
    }

    suspend fun set(context: Context, name: String, password: String): Pair<Boolean, String> {
        problem(name, password)?.let { return Pair(false, it) }
        val (code, out) = OemAppManager.executeCommand(context, buildSetCommand(context.packageName, name, password))
        val ok = code == 0 && out.contains("set")
        AppLog.i("hotspot: setting the name and password finished with code=$code ok=$ok")
        return Pair(ok, out.trim())
    }
}
