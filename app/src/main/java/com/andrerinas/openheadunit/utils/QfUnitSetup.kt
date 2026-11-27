package com.andrerinas.openheadunit.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkScreenPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What a QF unit is set to beyond the defaults of the settings themselves: the values pinned at every start, and what
 * the setup wizard chooses, which a reset of the settings has to put back. Does nothing on any other unit.
 */
object QfUnitSetup {

    private fun isQfUnit(context: Context): Boolean = ZlinkScreenPolicy.getInstalledPackages(context).isNotEmpty()

    /**
     * The rows that could change these are hidden on a zLink unit (except the choice of Wi-Fi and USB cable), so the
     * values are pinned to the ones that work: Native Android Auto, over zLink's hotspot (WiFi Direct shares the radio
     * with it and drops CarPlay).
     */
    fun pin(context: Context) {
        val settings = Settings(context)
        if (isQfUnit(context)) {
            // A standing 'Bluetooth could not send anything' banner from earlier goes too.
            ConnectionIssues.clear(context, ConnectionIssue.BLUETOOTH_SENT_NO_DATA)
            if (settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
                settings.wifiConnectionMode = WifiLauncherMode.NATIVE
            }
            // 5 GHz only (setting value 1), the one band that carries Android Auto video well.
            if (settings.hotspotBand != 1) settings.hotspotBand = 1
            // Wi-Fi stays chosen (it carries Android Auto and CarPlay). A phone on a USB cable is the user's to add;
            // Self Mode does not exist on this unit. Empty means "all", which is left alone.
            val wifi = Settings.ConnectionMode.WIFI
            val usb = Settings.ConnectionMode.USB
            val chosen = settings.connectionModes
            if (chosen.isNotEmpty()) {
                val pinned = (chosen - Settings.ConnectionMode.SELF) + wifi
                if (pinned != chosen) settings.connectionModes = pinned
            }
            // With a USB cable chosen, a phone on it connects by itself: when it is plugged in, when the home is
            // opened, and after the car starts (the ACC-on wake checks the cable when auto-start on USB is on). Set
            // once; the rows stay the user's to turn off.
            if (Settings.ConnectionMode.USB in settings.connectionModes) {
                if (!settings.hasStoredAutoStartOnUsb()) {
                    settings.autoStartOnUsb = true
                    Settings.syncAutoStartOnUsbToDeviceStorage(context, true)
                }
                if (!settings.hasStoredAutoConnectSingleUsbDevice()) settings.autoConnectSingleUsbDevice = true
            }
            if (settings.nativeApStrategy != NativeStrategy.HOTSPOT) {
                settings.nativeApStrategy = NativeStrategy.HOTSPOT
            }
        }
    }

    /** What has to be remembered across a reset. */
    data class BeforeReset(
        val displaySizePreset: String?,
        /** Null while the choice was never stored. */
        val hideZlinkUi: Boolean?,
        val carLauncher: Boolean,
        val touchPanelOverride: Boolean,
        val hardwareComposer: Boolean,
        val hideStatusBarButtons: Boolean,
        val screenFastRefresh: Boolean,
    )

    fun beforeReset(context: Context): BeforeReset {
        val settings = Settings(context)
        return BeforeReset(
            displaySizePreset = settings.displaySizePreset,
            hideZlinkUi = if (settings.hasStoredHideZlinkUi()) settings.hideZlinkUi else null,
            carLauncher = settings.enableCarLauncher,
            touchPanelOverride = settings.touchPanelOverride,
            hardwareComposer = settings.hardwareComposer,
            hideStatusBarButtons = settings.hideStatusBarButtons,
            screenFastRefresh = settings.screenFastRefresh,
        )
    }

    /**
     * Brings a unit whose settings were just cleared to the state the setup leaves it in. The display size, the hidden
     * zLink app and the launcher describe this unit rather than a preference, so they are kept. A touch panel or
     * compositor override that the reset turned off is taken back at once rather than at the next reboot.
     */
    fun afterReset(context: Context, before: BeforeReset) {
        val app = context.applicationContext
        if (!isQfUnit(app)) return
        val settings = Settings(app)
        before.displaySizePreset?.let { settings.displaySizePreset = it }
        before.hideZlinkUi?.let { settings.hideZlinkUi = it }
        settings.enableCarLauncher = before.carLauncher
        // The overlay stays on over a reset, so the switch has to go on saying so.
        settings.hideStatusBarButtons = before.hideStatusBarButtons
        // The screen keeps its rate over a reset too.
        settings.screenFastRefresh = before.screenFastRefresh
        val panel = DisplayMetrics()
        val displays = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(panel)
        if (panel.widthPixels > 0) {
            settings.resolutionId = SystemOptimizer.recommendedResolution(panel.widthPixels, panel.heightPixels).id
            settings.screenOrientation = if (panel.widthPixels >= panel.heightPixels) {
                Settings.ScreenOrientation.LANDSCAPE
            } else {
                Settings.ScreenOrientation.PORTRAIT
            }
        }
        pin(app)
        if (before.touchPanelOverride && !settings.touchPanelOverride) TouchPanelOverride.apply(app, false)
        if (before.hardwareComposer && !settings.hardwareComposer) HardwareComposer.apply(app, false)
        // With root as the way in, Root only goes back on once root is granted, as in the setup.
        CoroutineScope(Dispatchers.IO).launch {
            if (settings.shellRootPreferred && OemAppManager.requestRootNow()) settings.rootOnlyDebug = true
        }
    }
}
