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
        settings.projectionMode = mode
        if (mode == Settings.ProjectionMode.CARPLAY_ZLINK) {
            // Tear down any Android Auto session and leave the service idle; it ignores starts in this mode.
            ContextCompat.startForegroundService(app, Intent(app, AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            })
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                if (settings.hideZlinkUi) ZlinkScreenPolicy.applyVisibility(app, false)
                bringStationWifiUp(app)
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
        val (code, out) = OemAppManager.executeCommand(context, "svc wifi enable")
        AppLog.i("projection switch: station Wi-Fi enable finished with code=$code $out")
    }
}
