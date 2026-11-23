package com.andrerinas.openheadunit.connection.modulephone

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Learns which kind a phone is from a session that worked with it: a CarPlay session means an iPhone, a wireless
 * Android Auto session means an Android phone. Only a phone the module reports at the moment the session connects
 * is remembered.
 *
 * If AA or CarPlay are already running, switching the Bluetooth device before disconnect does not save it as an
 * iPhone or Android.
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
            var aaWasConnected = false
            combine(
                ModulePhoneWatcher.phone,
                ZlinkHub.hasActiveSessionFlow,
                comm.connectionState
            ) { phone, carPlayUp, _ ->
                Triple(phone, carPlayUp, comm.isConnected && !comm.isUsbSession && !comm.isLoopbackSession)
            }.collect { (phone, carPlayUp, isAaConnected) ->
                if (carPlayUp) {
                    if (!carPlayWasUp) {
                        // Rising edge: CarPlay session established
                        scope.launch {
                            kotlinx.coroutines.delay(SETTLE_MS)
                            ModulePhoneWatcher.snapshotOnce()?.let {
                                ModulePhoneWatcher.rememberMac(it.mac, PhoneKind.IPHONE)
                            }
                        }
                    }
                }
                carPlayWasUp = carPlayUp

                if (isAaConnected) {
                    if (!aaWasConnected) {
                        // Rising edge: AA just connected! Only learn the phone present at connect time.
                        // Switching bluetooth devices while AA is already running will not save as Android.
                        phone?.mac?.let { ModulePhoneWatcher.rememberMac(it, PhoneKind.ANDROID) }
                    }
                }
                aaWasConnected = isAaConnected
            }
        }
    }
}
