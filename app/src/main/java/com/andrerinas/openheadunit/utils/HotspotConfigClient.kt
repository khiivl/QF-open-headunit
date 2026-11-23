package com.andrerinas.openheadunit.utils

import android.content.Context

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

    /** The command for the adb shell; the caller has checked [problem]. */
    fun buildSetCommand(packageName: String, name: String, password: String): String =
        "APK=\$(pm path $packageName | head -1 | cut -d: -f2); " +
            "su 1000 sh -c \"CLASSPATH=\$APK app_process /system/bin " +
            "com.andrerinas.openheadunit.tools.HotspotConfigTool set '$name' '$password'\""

    suspend fun set(context: Context, name: String, password: String): Pair<Boolean, String> {
        problem(name, password)?.let { return Pair(false, it) }
        val (code, out) = OemAppManager.executeCommand(context, buildSetCommand(context.packageName, name, password))
        val ok = code == 0 && out.contains("set")
        AppLog.i("hotspot: setting the name and password finished with code=$code ok=$ok")
        return Pair(ok, out.trim())
    }
}
