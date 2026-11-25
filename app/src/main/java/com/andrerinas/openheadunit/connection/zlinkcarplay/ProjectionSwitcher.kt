package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The one place that changes the phone projection mode, shared by the Settings row and the Home tile. */
object ProjectionSwitcher {

    fun other(mode: Settings.ProjectionMode): Settings.ProjectionMode =
        if (mode == Settings.ProjectionMode.CARPLAY_ZLINK) Settings.ProjectionMode.ANDROID_AUTO
        else Settings.ProjectionMode.CARPLAY_ZLINK

    fun set(context: Context, mode: Settings.ProjectionMode) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (settings.projectionMode == mode) return
        // The other mode is disabled by the user's choice of one projection only.
        if (!ProjectionModePolicy.allows(settings.projectionAvailability, mode)) return
        settings.projectionMode = mode
        if (mode == Settings.ProjectionMode.CARPLAY_ZLINK) {
            // The phone connects only once the user presses Connect, not the moment the mode changes.
            ZlinkHub.holdOffUntilConnect()
            // Tear down any Android Auto session and leave the service idle; it ignores starts in this mode.
            ContextCompat.startForegroundService(app, Intent(app, AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
                putExtra(AapService.EXTRA_KEEP_HOME, true)
            })
        } else {
            // A CarPlay session must not outlive the switch: the hotspot can stay up and keep the iPhone connected.
            ZlinkHub.endSession(ZlinkHub.EndReason.USER)
            CoroutineScope(Dispatchers.IO).launch {
                // zLink's screens stay hidden: changing them stops its app, and the unit then opens its own
                // Bluetooth screen over OHU at every restart.
                // Only the WiFi Direct strategy needs the station side; the hotspot strategy keeps zLink's hotspot.
                if (settings.nativeApStrategy == com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy.WIFI_DIRECT) {
                    bringStationWifiUp(app)
                }
                // The Android Auto service is not started here: nothing connects on its own when the mode changes,
                // the Connect button starts it (and arms the wireless transport) when the user asks.
            }
        }
        ZlinkService.sync(app)
    }

    /**
     * CarPlay leaves zLink's hotspot running. The radio cannot run it and a WiFi Direct group together, and an app
     * may not switch the station side on (Android 10), so Android Auto's group never formed after a switch. The
     * shell can: turning the station Wi-Fi on makes the hotspot give way.
     */
    private suspend fun bringStationWifiUp(context: Context) {
        // CoD OS: OHU does not switch the unit's station Wi-Fi (owner, 24.11); the strategy is pinned to the hotspot anyway.
        if (com.andrerinas.openheadunit.BuildConfig.CODOS) return
        val (code, out) = OemAppManager.executeCommand(context, "svc wifi enable")
        AppLog.i("projection switch: station Wi-Fi enable finished with code=$code $out")
    }
}
