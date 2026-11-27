package com.andrerinas.openheadunit.app

/** Which screen to bring back once the unit's camera view has closed. */
object CameraReturnPolicy {

    /** A screen that left the front this shortly before the camera view appeared still counts as under it. */
    const val LEAD_MS = 3_000L

    /** How long after the view closes to look whether the screen is back. The unit's Home can come late. */
    val CHECKS_MS = listOf(400L, 1_200L, 2_500L, 4_000L)

    /**
     * @param front the projection screen that is in front as the camera view appears, if one is
     * @param last the one that last left the front, and [lastLeftAt] when it did
     * @return the screen the camera is covering, or null when it covers something else
     */
    fun <S : Any> screenUnderCamera(front: S?, last: S?, lastLeftAt: Long, now: Long): S? =
        front ?: last?.takeIf { now - lastLeftAt <= LEAD_MS }
}
