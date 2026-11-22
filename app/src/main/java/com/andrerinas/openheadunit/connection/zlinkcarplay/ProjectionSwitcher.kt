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
import kotlinx.coroutines.delay
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
                // CarPlay mode stops the Android Auto service. Starting it again arms its wireless transport
                // (the same plain start the boot receiver makes), so a phone that is already connected over
                // Bluetooth is picked up without going back to the Home screen first.
                if (Settings(app).projectionMode == Settings.ProjectionMode.ANDROID_AUTO) {
                    try {
                        ContextCompat.startForegroundService(app, Intent(app, AapService::class.java))
                        AppLog.i("projection switch: Android Auto service started")
                    } catch (e: Exception) {
                        AppLog.w("projection switch: could not start the Android Auto service: ${e.message}")
                    }
                }
            }
        }
        ZlinkService.sync(app)
        reclaimForeground(app)
    }

    /**
     * The unit's own Bluetooth screen opens itself whenever a phone connects or leaves the Bluetooth module, which a
     * mode switch always causes. For a short while after a switch, bring OHU back when that screen has taken over.
     */
    private fun reclaimForeground(app: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            var reclaimed = 0
            repeat(RECLAIM_WINDOW_SECONDS) {
                delay(1_000)
                if (reclaimed >= RECLAIM_MAX) return@launch
                val (code, out) = OemAppManager.executeCommand(app, "dumpsys activity activities | grep -m1 mResumedActivity")
                if (code == 0 && out.contains(UNIT_BLUETOOTH_PACKAGE)) {
                    reclaimed++
                    AppLog.i("projection switch: the unit's Bluetooth screen took over, returning to the home screen ($reclaimed)")
                    try {
                        app.startActivity(homeIntent(app))
                    } catch (e: Exception) {
                        AppLog.w("projection switch: could not return to the home screen: ${e.message}")
                    }
                }
            }
        }
    }

    private fun homeIntent(app: Context): Intent {
        val isHome = com.andrerinas.openheadunit.utils.CarLauncherManager.isDefaultLauncher(app)
        val intent = if (isHome) {
            Intent(app, com.andrerinas.openheadunit.main.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_HOME)
            }
        } else {
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private const val RECLAIM_WINDOW_SECONDS = 20
    private const val RECLAIM_MAX = 3
    private const val UNIT_BLUETOOTH_PACKAGE = "com.qf.bluetooth"

    /**
     * CarPlay leaves zLink's hotspot running. The radio cannot run it and a WiFi Direct group together, and an app
     * may not switch the station side on (Android 10), so Android Auto's group never formed after a switch. The
     * shell can: turning the station Wi-Fi on makes the hotspot give way.
     */
    private suspend fun bringStationWifiUp(context: Context) {
        val (code, out) = OemAppManager.executeCommand(context, "svc wifi enable")
        AppLog.i("projection switch: station Wi-Fi enable finished with code=$code $out")
    }
}
