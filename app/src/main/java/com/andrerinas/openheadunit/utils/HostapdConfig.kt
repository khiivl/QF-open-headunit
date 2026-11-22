package com.andrerinas.openheadunit.utils

/**
 * Reads the name and passphrase of an access point from a hostapd configuration, for units whose hotspot is run by
 * the system's hostapd directly (the Android settings never hold its credentials).
 */
object HostapdConfig {

    /** Where the units this was written for keep the file; the shell command prints whichever exist. */
    const val READ_COMMAND =
        "cat /data/vendor/wifi/hostapd/hostapd_*.conf /data/misc/wifi/hostapd.conf 2>/dev/null"

    /** (ssid, passphrase) from the configuration text, or null without a name and a passphrase. */
    fun parse(text: String): Pair<String, String>? {
        var ssid: String? = null
        var passphrase: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#")) continue
            val at = line.indexOf('=')
            if (at <= 0) continue
            val key = line.substring(0, at)
            val value = line.substring(at + 1)
            when (key) {
                "ssid2" -> if (ssid == null) ssid = decodeHex(value)
                "ssid" -> if (ssid == null) ssid = value
                "wpa_passphrase" -> if (passphrase == null) passphrase = value
            }
        }
        val name = ssid?.takeIf { it.isNotEmpty() } ?: return null
        val pass = passphrase?.takeIf { it.isNotEmpty() } ?: return null
        return Pair(name, pass)
    }

    private fun decodeHex(hex: String): String? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val v = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[i] = v.toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }
}
