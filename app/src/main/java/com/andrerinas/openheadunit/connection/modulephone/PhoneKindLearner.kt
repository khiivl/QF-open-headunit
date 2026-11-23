package com.andrerinas.openheadunit.connection.modulephone

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.zlinkcarplay.ProjectionModePolicy
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkHub
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Learns which kind a phone is from a session that worked with it: a CarPlay session means an iPhone, a wireless
 * Android Auto session means an Android phone. Only a phone the module reports at that moment is remembered.
 */
object PhoneKindLearner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false
    private const val SETTLE_MS = 5_000L

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val comm = App.provide(app).commManager
            var carPlayWasUp = false
            combine(
                ModulePhoneWatcher.phone,
                ZlinkHub.hasActiveSessionFlow,
                comm.connectionState
            ) { phone, carPlayUp, _ -> Pair(phone, carPlayUp) }.collect { (phone, carPlayUp) ->
                val carPlayMode = ProjectionModePolicy.carPlayEnabled(Settings(app).projectionMode)
                if (carPlayMode) {
                    // The watcher stands down in CarPlay mode, so the phone is read once, a few seconds after the
                    // session came up, when nothing is being set up over the module any more.
                    if (carPlayUp && !carPlayWasUp) {
                        scope.launch {
                            kotlinx.coroutines.delay(SETTLE_MS)
                            ModulePhoneWatcher.snapshotOnce()?.let { ModulePhoneWatcher.rememberMac(it.mac, PhoneKind.IPHONE) }
                        }
                    }
                    carPlayWasUp = carPlayUp
                } else if (phone?.mac != null && comm.isConnected && !comm.isUsbSession && !comm.isLoopbackSession) {
                    ModulePhoneWatcher.rememberConnected(PhoneKind.ANDROID)
                }
            }
        }
    }
}
