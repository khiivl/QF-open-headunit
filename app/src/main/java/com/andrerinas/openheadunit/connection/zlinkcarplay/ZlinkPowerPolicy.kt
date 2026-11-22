package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.Intent
import com.andrerinas.openheadunit.aap.AapService

/**
 * Pure policy deciding when head unit power transitions should shut down or resume CarPlay.
 *
 * Rules:
 * - Off signals: Any in [AapService.ACC_OFF_ACTIONS], [Intent.ACTION_SHUTDOWN], or [Intent.ACTION_SCREEN_OFF].
 * - On signals: Any in [AapService.ACC_ON_ACTIONS], [Intent.ACTION_SCREEN_ON], or [Intent.ACTION_BOOT_COMPLETED].
 * - [Intent.ACTION_POWER_DISCONNECTED] alone does nothing.
 * - [Intent.ACTION_SCREEN_OFF] ends the session only after 3 s with no ACC-on.
 */
class ZlinkPowerPolicy(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val listener: Listener
) {
    interface Listener {
        fun onPowerOff(action: String)
        fun onPowerOn(action: String)
    }

    private var pendingScreenOffAt: Long? = null
    private var pendingScreenOffAction: String? = null

    val hasPendingScreenOff: Boolean
        get() = pendingScreenOffAt != null

    fun onAction(action: String, now: Long = clock()) {
        when {
            action == Intent.ACTION_SCREEN_OFF -> {
                pendingScreenOffAt = now
                pendingScreenOffAction = action
            }

            action in AapService.ACC_OFF_ACTIONS || action == Intent.ACTION_SHUTDOWN -> {
                pendingScreenOffAt = null
                pendingScreenOffAction = null
                listener.onPowerOff(action)
            }

            action in AapService.ACC_ON_ACTIONS ||
            action == Intent.ACTION_SCREEN_ON ||
            action == Intent.ACTION_BOOT_COMPLETED -> {
                pendingScreenOffAt = null
                pendingScreenOffAction = null
                listener.onPowerOn(action)
            }

            action == Intent.ACTION_POWER_DISCONNECTED -> {
                // ACTION_POWER_DISCONNECTED alone does nothing
            }

            else -> {
                // Ignored
            }
        }
    }

    fun tick(now: Long = clock()) {
        val screenOffAt = pendingScreenOffAt ?: return
        if (now - screenOffAt >= SCREEN_OFF_DELAY_MS) {
            val act = pendingScreenOffAction ?: Intent.ACTION_SCREEN_OFF
            pendingScreenOffAt = null
            pendingScreenOffAction = null
            listener.onPowerOff(act)
        }
    }

    companion object {
        const val SCREEN_OFF_DELAY_MS = 3_000L
    }
}
