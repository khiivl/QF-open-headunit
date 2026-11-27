package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.andrerinas.openheadunit.app.AccPowerState
import com.andrerinas.openheadunit.connection.UserCancelHold
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkScreenPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SoftApStateReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Switches the hotspot on as soon as the app's process exists, on a unit that never starts it by itself.
 *
 * A zLink unit boots as a Wi-Fi client, and the hotspot takes about nine seconds to come up once asked. The ask used to
 * wait for the Android Auto bring-up, which on a booting unit starts about five seconds after the process does (the
 * main thread is busy), plus its own wait, so the phone's join waited for all of it. Asking from a background thread at
 * process start overlaps the hotspot's start-up with that work. [SoftApCredentialsProvider] treats an ask made here as
 * in flight, so the two never toggle it twice.
 */
object EarlyHotspot {
    @Volatile private var inFlight = false
    @Volatile private var lastEndedAtMs = 0L
    @Volatile private var lastSucceeded = false

    /** An ask made here is running, or came back a moment ago and the hotspot may still be on its way up. */
    fun comingUp(nowMs: Long): Boolean =
        SoftApAutoEnablePolicy.comingUp(inFlight, lastSucceeded, lastEndedAtMs, nowMs)

    fun start(context: Context) {
        val app = context.applicationContext
        if (UserCancelHold.isHeld(app)) {
            AppLog.i("EarlyHotspot: the user cancelled the connection, leaving the hotspot alone.")
            return
        }
        val settings = Settings(app)
        val zlinkUnit = ZlinkScreenPolicy.getInstalledPackages(app).isNotEmpty()
        val apEnabled = SoftApStateReader.read(app) == SoftApState.ENABLED
        if (!EarlyHotspotPolicy.shouldStart(settings.autoEnableHotspot, zlinkUnit, apEnabled, AccPowerState.isOff, screenIsOff(app), inFlight)) return
        inFlight = true
        CoroutineScope(Dispatchers.IO).launch {
            AppLog.i("EarlyHotspot: the process is up and no hotspot is running, switching it on now.")
            try {
                lastSucceeded = HotspotManager.setHotspotEnabled(app, true)
            } catch (e: Exception) {
                lastSucceeded = false
                AppLog.w("EarlyHotspot: could not switch the hotspot on: ${e.message}")
            } finally {
                lastEndedAtMs = System.currentTimeMillis()
                inFlight = false
            }
        }
    }

    private fun screenIsOff(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        !(if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn)
    } catch (e: Exception) {
        false
    }
}

/** Whether to ask for the hotspot at process start. */
object EarlyHotspotPolicy {
    fun shouldStart(
        autoEnableSetting: Boolean,
        zlinkUnit: Boolean,
        accessPointEnabled: Boolean,
        accOff: Boolean,
        screenOff: Boolean,
        alreadyAsked: Boolean,
    ): Boolean = autoEnableSetting && zlinkUnit && !accessPointEnabled && !accOff && !screenOff && !alreadyAsked
}
