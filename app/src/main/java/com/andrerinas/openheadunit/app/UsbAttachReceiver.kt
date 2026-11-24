package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.connection.zlinkcarplay.ProjectionModePolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Connects a phone plugged into the USB cable as soon as it is seen, whether or not the Android Auto service is up.
 *
 * Plug-in used to depend on the service being alive: its own USB receiver did the connecting, and the card's "phone
 * detected" only came from the screen's receiver, which connects nothing. ACC off now stops the service, and the manifest
 * activity Android should launch for an attach competes with other apps for the same event, so a phone plugged in with no
 * service running was detected and then ignored. This receiver is registered once for the life of the process and starts
 * the service with the same plug-in check the attach activity uses.
 */
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = UsbReceiver.deviceOf(intent) ?: return
        val settings = Settings(context)
        if (ProjectionModePolicy.carPlayEnabled(settings.projectionMode)) return
        if (!settings.showsUsb() || !settings.effectiveConnectOnPlugIn) return
        if (!UsbDeviceCompat.isConnectable(context, device)) return
        if (App.provide(context).commManager.isConnected) return
        AppLog.i("UsbAttach: ${device.deviceName} attached, starting the Android Auto service to connect it")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AapService::class.java)
                    .setAction(AapService.ACTION_CHECK_USB)
                    .putExtra(AapService.EXTRA_USB_TRIGGER, AapService.USB_TRIGGER_PLUG_IN)
            )
        } catch (e: IllegalStateException) {
            AppLog.w("UsbAttach: Android refused to start the service: ${e.message}")
        }
    }

    companion object {
        @Volatile private var registered = false

        /** Registers the receiver once; safe to call again. */
        @Synchronized
        fun register(context: Context) {
            if (registered) return
            ContextCompat.registerReceiver(
                context.applicationContext, UsbAttachReceiver(),
                IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }
    }
}
