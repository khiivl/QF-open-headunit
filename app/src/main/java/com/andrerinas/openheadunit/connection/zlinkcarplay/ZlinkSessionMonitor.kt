package com.andrerinas.openheadunit.connection.zlinkcarplay

/**
 * Pure monitor tracking connection health between the zLink daemon and OHU.
 *
 * Inputs:
 * - Daemon heartbeat (type 0x118 on control, every 2 s)
 * - Daemon socket closed
 * - Video / audio frames (especially SPS)
 *
 * States:
 * - CONNECTED: Normal streaming / heartbeat exchange.
 * - LOST: No heartbeat for 7 s or daemon socket closed.
 * - GAVE_UP: 15 s elapsed in LOST without recovery.
 */
class ZlinkSessionMonitor(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onStateChanged: (ZlinkSessionState) -> Unit = {}
) {
    var state: ZlinkSessionState = ZlinkSessionState.CONNECTED
        private set

    private var lastHeartbeatTime: Long = clock()
    private var lostSinceTime: Long = 0L

    fun onHeartbeat(now: Long = clock()) {
        lastHeartbeatTime = now
        if (state == ZlinkSessionState.LOST || state == ZlinkSessionState.GAVE_UP) {
            transitionTo(ZlinkSessionState.CONNECTED)
        }
    }

    fun onSps(now: Long = clock()) {
        lastHeartbeatTime = now
        if (state == ZlinkSessionState.LOST || state == ZlinkSessionState.GAVE_UP) {
            transitionTo(ZlinkSessionState.CONNECTED)
        }
    }

    fun onMediaFrame(now: Long = clock()) {
        // Media activity confirms connection
        lastHeartbeatTime = now
        if (state == ZlinkSessionState.LOST || state == ZlinkSessionState.GAVE_UP) {
            transitionTo(ZlinkSessionState.CONNECTED)
        }
    }

    fun onDaemonSocketClosed(now: Long = clock()) {
        if (state == ZlinkSessionState.CONNECTED) {
            lostSinceTime = now
            transitionTo(ZlinkSessionState.LOST)
        }
    }

    fun reset(now: Long = clock()) {
        lastHeartbeatTime = now
        lostSinceTime = 0L
        transitionTo(ZlinkSessionState.CONNECTED)
    }

    fun markGaveUp(now: Long = clock()) {
        lostSinceTime = now
        transitionTo(ZlinkSessionState.GAVE_UP)
    }

    /**
     * Checks time-based state transitions. Call periodically (e.g. every second).
     */
    fun tick(now: Long = clock()) {
        when (state) {
            ZlinkSessionState.CONNECTED -> {
                if (now - lastHeartbeatTime >= HEARTBEAT_TIMEOUT_MS) {
                    lostSinceTime = now
                    transitionTo(ZlinkSessionState.LOST)
                }
            }
            ZlinkSessionState.LOST -> {
                if (now - lostSinceTime >= GIVE_UP_TIMEOUT_MS) {
                    transitionTo(ZlinkSessionState.GAVE_UP)
                }
            }
            ZlinkSessionState.GAVE_UP -> {
                // Stays in GAVE_UP until a new session's SPS or heartbeat arrives
            }
        }
    }

    private fun transitionTo(newState: ZlinkSessionState) {
        if (state != newState) {
            state = newState
            onStateChanged(newState)
        }
    }

    companion object {
        const val HEARTBEAT_TIMEOUT_MS = 7_000L
        const val GIVE_UP_TIMEOUT_MS = 15_000L
    }
}
