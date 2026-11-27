package com.andrerinas.openheadunit.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Switches SurfaceFlinger's use of the Hardware Composer, the "Disable HW overlays" developer switch. A QF K706 starts
 * with it off, so every layer is composed by the GPU; with it on, the video layer goes to a display overlay.
 *
 * The switch does not survive a reboot, so it is set again at each app start while the setting is on. With the setting
 * never turned on, the app does not touch it. The call needs a shell with system rights: root, or this unit's ADB.
 */
object HardwareComposer {

    /** How long a trial lasts before the composer goes off again by itself. */
    const val TRIAL_SECONDS = 15

    fun command(enabled: Boolean): String = "service call SurfaceFlinger 1008 i32 ${if (enabled) 0 else 1}"

    /** Sets it after [delayMs], which gives the shell time to come up at an app start. */
    fun apply(context: Context, enabled: Boolean, delayMs: Long = 0L) {
        // CoD OS: the hardware composer has one switch, the system one (CarSettings, key persist.sys.qf001.hwc, written only by
        // the framework's HwcTrial). This app neither calls SurfaceFlinger 1008 nor writes a property of its own there.
        if (com.andrerinas.openheadunit.BuildConfig.CODOS) {
            AppLog.i("HardwareComposer: not set (CoD OS keeps one system switch for it)")
            return
        }
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            val (code, out) = try {
                OemAppManager.executeCommand(app, command(enabled))
            } catch (e: Exception) {
                -1 to (e.message ?: e.javaClass.simpleName)
            }
            AppLog.i("HardwareComposer: set enabled=$enabled, code=$code ${out.take(120)}")
        }
    }
}
