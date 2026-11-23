package com.andrerinas.openheadunit.tools

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper

/**
 * Reads and sets the unit's own hotspot name and password (Android's hotspot configuration). Meant to be run as root
 * through the local adb bridge, where the platform allows what an ordinary app is refused:
 *
 *     CLASSPATH=<path to this apk> app_process /system/bin com.andrerinas.openheadunit.tools.HotspotConfigTool get
 *     CLASSPATH=<path to this apk> app_process /system/bin com.andrerinas.openheadunit.tools.HotspotConfigTool set <name> <password>
 *
 * `get` prints the name and the length of the password, never the password itself.
 */
object HotspotConfigTool {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            System.exit(run(args))
        } catch (e: Throwable) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            println("error: ${cause.javaClass.simpleName}: ${cause.message}")
            cause.stackTrace.take(4).forEach { println("  at $it") }
            System.exit(1)
        }
    }

    private fun run(args: Array<String>): Int {
        if (args.isEmpty()) {
            println("usage: get | set <name> <password> | ensure-5ghz")
            return 2
        }
        val wm = wifiManager()
        val current = wm.javaClass.getMethod("getWifiApConfiguration").invoke(wm) as? WifiConfiguration
        when (args[0]) {
            "get" -> {
                if (current == null) { println("no hotspot configuration"); return 1 }
                println("ssid=${current.SSID} passwordLength=${current.preSharedKey?.length ?: 0} band=${apBand(current)}")
                return 0
            }
            "set" -> {
                if (args.size < 3) { println("usage: set <name> <password>"); return 2 }
                val name = args[1]
                val password = args[2]
                if (name.isEmpty() || name.length > 32) { println("error: the name must be 1 to 32 characters"); return 2 }
                if (password.length !in 8..63) { println("error: the password must be 8 to 63 characters"); return 2 }
                // Start from the configuration in place, so the band, the channel and the rest stay as they are.
                val config = current ?: WifiConfiguration()
                config.SSID = name
                config.preSharedKey = password
                config.allowedKeyManagement.clear()
                config.allowedKeyManagement.set(4) // WifiConfiguration.KeyMgmt.WPA2_PSK (hidden constant)
                val ok = wm.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wm, config) as Boolean
                println(if (ok) "set" else "refused")
                return if (ok) 0 else 1
            }
            "ensure-5ghz" -> {
                // The hotspot stays on 5 GHz: the only band measured to carry Android Auto video at full rate. Anything
                // else is put back, the hotspot following the change by itself within seconds.
                if (current == null) { println("no hotspot configuration"); return 1 }
                if (apBand(current) == BAND_5GHZ) { println("already 5 GHz"); return 0 }
                WifiConfiguration::class.java.getField("apBand").setInt(current, BAND_5GHZ)
                val ok = wm.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wm, current) as Boolean
                println(if (ok) "set 5 GHz" else "refused")
                return if (ok) 0 else 1
            }
            else -> { println("usage: get | set <name> <password> | ensure-5ghz"); return 2 }
        }
    }

    /** WifiConfiguration.AP_BAND_5GHZ (a hidden constant). */
    private const val BAND_5GHZ = 1

    private fun apBand(config: WifiConfiguration): Int = WifiConfiguration::class.java.getField("apBand").getInt(config)

    private fun wifiManager(): WifiManager {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val context = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        return context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
}
