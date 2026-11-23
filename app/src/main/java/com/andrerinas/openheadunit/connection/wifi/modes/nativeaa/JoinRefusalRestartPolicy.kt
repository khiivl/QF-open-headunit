package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * When a phone that keeps refusing the join should get a fresh attempt instead of the same one again.
 *
 * Measured on a zLink unit: a phone answers the Bluetooth handshake, takes the credentials and reports "network
 * unavailable" within a fraction of a second, again and again for minutes, while the same credentials work on the very
 * next attempt that starts from scratch (the user's cancel and reconnect, twice). Resending only reopens the Bluetooth
 * channel; a restart rebuilds the whole bring-up. The count of restarts is kept outside the handshake manager, which a
 * restart replaces, and cleared when a session lands.
 */
object JoinRefusalRestartPolicy {
    const val REFUSALS_BEFORE_RESTART = 2
    const val MAX_RESTARTS = 2

    @Volatile var restartsSinceLanding = 0
        private set

    fun shouldRestart(consecutiveRefusals: Int, restartsSoFar: Int, zlinkUnit: Boolean): Boolean =
        zlinkUnit && consecutiveRefusals >= REFUSALS_BEFORE_RESTART && restartsSoFar < MAX_RESTARTS

    fun noteRestart() { restartsSinceLanding++ }

    fun noteLanded() { restartsSinceLanding = 0 }
}
