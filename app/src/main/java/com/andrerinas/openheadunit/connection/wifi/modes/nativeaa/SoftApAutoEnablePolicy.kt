package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether to ask for the hotspot again while none is on air.
 *
 * An enable within about 10 s of a radio change fails, so none that soon after the AP drops, and a
 * failed one gets one more, spaced from its failure. None while the car is off or the screen is dark,
 * nor just after a wake: the unit restores its own AP.
 */
object SoftApAutoEnablePolicy {

    const val FIRST_ATTEMPT_AFTER_MS = 5_000L
    const val RETRY_AFTER_MS = 10_000L
    const val MAX_ATTEMPTS = 2
    // An XYAuto unit was measured restoring its own AP about 4 s after ACC-on.
    const val FIRST_ATTEMPT_AFTER_WAKE_MS = 15_000L

    /** [lastAttemptEndedAtMs] is when the last start returned; the `since` inputs are null without the event. */
    fun shouldAttempt(
        enabledInSettings: Boolean,
        attempts: Int,
        waitedMs: Long,
        lastAttemptEndedAtMs: Long,
        lastAttemptSucceeded: Boolean,
        nowMs: Long,
        accOff: Boolean = false,
        attemptInFlight: Boolean = false,
        sinceWakeMs: Long? = null,
        sinceApDownMs: Long? = null,
        screenOff: Boolean = false,
    ): Boolean {
        if (!enabledInSettings || accOff || screenOff || attemptInFlight || attempts >= MAX_ATTEMPTS) return false
        if (sinceWakeMs != null && sinceWakeMs < FIRST_ATTEMPT_AFTER_WAKE_MS) return false
        if (sinceApDownMs != null && sinceApDownMs < RETRY_AFTER_MS) return false
        if (attempts == 0) return waitedMs >= FIRST_ATTEMPT_AFTER_MS
        return !lastAttemptSucceeded && nowMs - lastAttemptEndedAtMs >= RETRY_AFTER_MS
    }

    /**
     * How long after a start that went through the access point's network interface is still allowed to be on its way.
     * Measured on a UNISOC unit at about 50 s from "hotspot is up" to the interface existing, so a minute and a half.
     */
    const val COMING_UP_GRACE_MS = 90_000L

    /**
     * Whether a hotspot this app just switched on may still be coming up, so that "no access point" is not yet a
     * finding. False when nothing was started, or the start did not go through.
     */
    fun comingUp(attemptInFlight: Boolean, lastAttemptSucceeded: Boolean, lastAttemptEndedAtMs: Long, nowMs: Long): Boolean =
        attemptInFlight || (lastAttemptSucceeded && lastAttemptEndedAtMs != 0L && nowMs - lastAttemptEndedAtMs < COMING_UP_GRACE_MS)

    /** Whether the resolve loop should keep polling past its budget, because an attempt is still owed. */
    fun attemptOwed(enabledInSettings: Boolean, attempts: Int, lastAttemptSucceeded: Boolean, attemptInFlight: Boolean): Boolean =
        enabledInSettings && (attemptInFlight || (attempts < MAX_ATTEMPTS && !lastAttemptSucceeded))
}
