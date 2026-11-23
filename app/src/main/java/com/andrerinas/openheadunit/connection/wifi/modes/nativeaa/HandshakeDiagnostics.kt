package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the hotspot looked like at the two moments that decide a wireless join: when the credentials go out to the
 * phone, and when the phone says it could not join.
 *
 * A phone that refuses the same credentials again and again, while a fresh attempt a moment later works, leaves
 * nothing in the ordinary log to tell the two apart. This writes, for each of those moments, who is on the hotspot,
 * what the radio's own address is next to the address that was sent, whether hostapd is running, which sockets are
 * listening on the Android Auto ports, and how long the hotspot has been up. It runs off the caller's thread and
 * only reads.
 */
object HandshakeDiagnostics {
    // 5277 and 5288, the Android Auto head unit ports, are 149D and 14A8 in the kernel's socket tables.
    private const val COMMAND =
        "echo '[neighbours]'; cat /proc/net/arp 2>&1 | head -10; " +
            "echo '[radio]'; ip -o link show wlan0 2>&1 | head -2; ip -o addr show wlan0 2>&1 | head -2; " +
            "echo '[hostapd]'; ps -A 2>&1 | grep -c '[h]ostapd'; " +
            "echo '[sockets]'; grep -i -E ':(149D|14A8) ' /proc/net/tcp /proc/net/tcp6 2>&1 | head -8"

    @Volatile private var apSeenAtMs = 0L

    /** The hotspot's interface was found with an address; the first call per hotspot start fixes the clock. */
    fun noteApSeen() {
        if (apSeenAtMs == 0L) apSeenAtMs = SystemClock.elapsedRealtime()
    }

    fun noteApGone() { apSeenAtMs = 0L }

    fun snapshot(context: Context, event: String, detail: String) {
        val upFor = if (apSeenAtMs == 0L) "unknown" else "${(SystemClock.elapsedRealtime() - apSeenAtMs) / 1000}s"
        val app = context.applicationContext
        AppLog.i("HandshakeDiagnostics: $event | $detail | access point seen $upFor ago | reading the hotspot's state")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, out) = OemAppManager.executeCommand(app, COMMAND)
                AppLog.i("HandshakeDiagnostics: $event | $detail | access point seen $upFor ago | exit $code\n$out")
            } catch (e: Exception) {
                AppLog.w("HandshakeDiagnostics: $event | $detail | snapshot failed: ${e.message}")
            }
        }
    }
}
