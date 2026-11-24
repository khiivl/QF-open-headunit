package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.zlinkcarplay.ProjectionModePolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Starts the Android Auto service when the car comes back on, whatever else is running.
 *
 * ACC off stops the service, and the service held the only listener for ACC on: the broadcast is implicit, so the
 * manifest receiver ([BootCompleteReceiver]) is not given the unit's real one (only an explicit, package-addressed
 * one reaches it), and the CarPlay service listens only in CarPlay mode. This receiver is registered once for the
 * life of the process, so ACC on always finds something listening and the phone reconnects.
 */
class AccOnReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in AapService.ACC_ON_ACTIONS) return
        val settings = Settings(context)
        if (!settings.autoReconnectOnAccOn) {
            AppLog.i("AccOn: auto reconnect on ACC on is off, not starting the service ($action)")
            return
        }
        if (ProjectionModePolicy.carPlayEnabled(settings.projectionMode)) {
            // CarPlay has its own always-on service and its own power receiver.
            AppLog.i("AccOn: CarPlay is the mode, leaving the wake to its service ($action)")
            return
        }
        AppLog.i("AccOn: the car is back on, starting the Android Auto service so the phone reconnects ($action)")
        try {
            // A plain start: the service arms its listeners and checks for a phone on the cable on its own.
            ContextCompat.startForegroundService(context, Intent(context, AapService::class.java))
        } catch (e: IllegalStateException) {
            AppLog.w("AccOn: Android refused to start the service ($action): ${e.message}")
        }
    }

    companion object {
        @Volatile private var registered = false

        /** Registers the receiver once; safe to call again. */
        @Synchronized
        fun register(context: Context) {
            if (registered) return
            val filter = IntentFilter().apply { AapService.ACC_ON_ACTIONS.forEach { addAction(it) } }
            ContextCompat.registerReceiver(
                context.applicationContext, AccOnReceiver(), filter, ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
        }
    }
}
