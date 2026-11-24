package com.andrerinas.openheadunit.connection.zlinkcarplay

/**
 * What the home should say while CarPlay is connecting, from signals the unit really has.
 *
 * CarPlay is brought up by the zLink daemon and tells the app nothing about its steps, so after the car starts the home
 * said nothing for as long as the phone took. The step shown is the honest one: the offer is out and the phone has not
 * shown up ([Step.WAKING]); a phone is on the Bluetooth module and the session is on its way ([Step.CONNECTING]); or
 * there is nothing to show any more ([Step.STOP]): the session is up, the user held the offer off, or the wait ran out.
 */
object ZlinkConnectProgressPolicy {

    /** Longer than any connect seen on this unit, so a phone that never comes does not leave a progress bar running. */
    const val DEADLINE_MS = 90_000L

    /** A phone on the module for this long is a connection in progress, not a phone just passing. */
    const val CONNECTING_AFTER_PHONE_MS = 2_000L

    enum class Step { WAKING, CONNECTING, STOP }

    /** [phoneOnModuleForMs] is how long a phone has been on the module, or null when none is. */
    fun step(
        sessionActive: Boolean,
        offerSuspended: Boolean,
        elapsedMs: Long,
        phoneOnModuleForMs: Long?
    ): Step = when {
        sessionActive || offerSuspended || elapsedMs >= DEADLINE_MS -> Step.STOP
        phoneOnModuleForMs != null && phoneOnModuleForMs >= CONNECTING_AFTER_PHONE_MS -> Step.CONNECTING
        else -> Step.WAKING
    }
}
