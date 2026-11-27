package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.AccPowerState
import com.andrerinas.openheadunit.connection.UserCancelHold
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runtime receiver listening for head unit ignition / power state changes.
 * Feeds signals to [ZlinkPowerPolicy] and executes power down / wake transitions.
 */
class ZlinkPowerReceiver : BroadcastReceiver(), Closeable {
    @Volatile private var appContext: Context? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zlink-power-timer").also { it.isDaemon = true }
    }

    val policy = ZlinkPowerPolicy(
        listener = object : ZlinkPowerPolicy.Listener {
            override fun onPowerOff(action: String) {
                AppLog.i("zlink power: off triggered by $action")
                ZlinkHub.endSession(ZlinkHub.EndReason.POWER)
                ZlinkCarPlayActivity.finishActive()
                AccPowerState.noteOff(action)
            }

            override fun onPowerOn(action: String) {
                AppLog.i("zlink power: on triggered by $action")
                AccPowerState.noteWake()
                val ctx = appContext
                if (ctx != null && UserCancelHold.isHeld(ctx)) {
                    AppLog.i("zlink power: the user cancelled the connection, staying disconnected")
                } else if (ctx == null || Settings(ctx).autoReconnectOnAccOn) {
                    ZlinkHub.resumeOffer()
                } else {
                    AppLog.i("zlink power: auto reconnect on ACC on is off, staying disconnected")
                }
            }
        }
    )

    override fun onReceive(context: Context, intent: Intent) {
        appContext = context.applicationContext
        val action = intent.action ?: return
        AppLog.i("zlink power: broadcast received action=$action")
        policy.onAction(action)
        if (action == Intent.ACTION_SCREEN_OFF) {
            scheduler.schedule({
                policy.tick()
            }, ZlinkPowerPolicy.SCREEN_OFF_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    companion object {
        fun createFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            AapService.ACC_OFF_ACTIONS.forEach { addAction(it) }
            AapService.ACC_ON_ACTIONS.forEach { addAction(it) }
        }
    }
}
