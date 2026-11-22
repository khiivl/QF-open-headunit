package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapService
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
        } else if (settings.hideZlinkUi) {
            CoroutineScope(Dispatchers.IO).launch { ZlinkScreenPolicy.applyVisibility(app, false) }
        }
        ZlinkService.sync(app)
    }
}
