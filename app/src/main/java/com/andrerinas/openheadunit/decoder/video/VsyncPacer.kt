package com.andrerinas.openheadunit.decoder.video

/**
 * Gives each decoded frame its own display refresh, so a SurfaceView shows them on the display's beat instead of the
 * instant they happen to be decoded.
 *
 * A SurfaceView with an immediate release is the shortest path to the screen, and on the QF units it judders: frames reach
 * the decoder's output unevenly, each is shown on the next refresh whenever that is, and the motion inherits the
 * unevenness. TextureView and GLES smooth it because they draw on the display's cadence. This asks MediaCodec for the same
 * thing without the extra GPU pass: `releaseOutputBuffer(index, renderTimestampNs)` takes a time, and the display service
 * holds the buffer until the refresh at or after it.
 *
 * Pure and clock-free, so it can be tested without a device: the caller supplies the display's beat (an anchor refresh time
 * and the period) and "now", both on the `System.nanoTime()` clock that release timestamps use.
 *
 * Two rules do the work:
 *  - A frame is aimed at the first refresh at or after `now + lead`. The lead is the time the display service needs to take
 *    a buffer before a refresh, so a frame decoded just ahead of one does not miss it and slip a whole refresh late.
 *  - No two frames share a refresh. A frame that would land on one already taken goes to the next, so a late frame followed
 *    by an on-time one are shown in order, one refresh apart, instead of the first being replaced by the second.
 *
 * Latency is bounded: when the aim is more than [MAX_AHEAD_PERIODS] refreshes away, the schedule has fallen behind a burst,
 * and the frame is shown now (null) so the pacing never turns into a growing queue.
 */
class VsyncPacer(initialLeadNs: Long = DEFAULT_LEAD_NS) {

    /** The margin before a refresh a frame must be ready by. Changeable while running, so values can be compared live. */
    @Volatile var leadNs: Long = initialLeadNs

    /** One immutable pair, so the main thread's update is seen whole by the output thread. */
    private class Beat(val anchorNs: Long, val periodNs: Long)

    @Volatile private var beat: Beat? = null
    private var lastTargetNs = Long.MIN_VALUE

    // Window figures for the log, reset by [consumeStats].
    private var scheduled = 0
    private var shownNow = 0
    private var waitSumNs = 0L
    private var waitMaxNs = 0L

    /** The display's beat: a refresh that happened at [anchorNs] and the time between refreshes. */
    fun setBeat(anchorNs: Long, periodNs: Long) {
        beat = Beat(anchorNs, periodNs)
    }

    /**
     * The time to hand `releaseOutputBuffer` for a frame decoded at [nowNs], or null when it should be shown at once: the
     * beat is not known yet, or the frame is too far behind the schedule to wait for its turn.
     */
    fun releaseTimeNs(nowNs: Long): Long? {
        val current = beat ?: return null
        val anchorNs = current.anchorNs
        val periodNs = current.periodNs
        if (periodNs <= 0L || anchorNs == 0L) return null
        val earliest = nowNs + leadNs
        val sinceAnchor = earliest - anchorNs
        var refreshes = Math.floorDiv(sinceAnchor, periodNs)
        if (sinceAnchor - refreshes * periodNs > 0L) refreshes++
        var target = anchorNs + refreshes * periodNs
        if (lastTargetNs != Long.MIN_VALUE && target <= lastTargetNs) target = lastTargetNs + periodNs
        if (target - nowNs > MAX_AHEAD_PERIODS * periodNs) {
            // Behind a burst: show this one now and start the schedule over from the next frame.
            lastTargetNs = Long.MIN_VALUE
            shownNow++
            return null
        }
        lastTargetNs = target
        val wait = target - nowNs
        scheduled++
        waitSumNs += wait
        if (wait > waitMaxNs) waitMaxNs = wait
        return target
    }

    /** Forgets the schedule, for a new session or a surface that came back: the old refresh slots mean nothing now. */
    fun reset() {
        lastTargetNs = Long.MIN_VALUE
    }

    /** What the window did, as a log fragment, or null when nothing was scheduled; clears the window. */
    fun consumeStats(): String? {
        if (scheduled == 0 && shownNow == 0) return null
        val avgMs = if (scheduled == 0) 0.0 else waitSumNs / scheduled / 1e6
        val text = "paced=$scheduled, pacedNow=$shownNow, pacedWait=%.1fms max=%.1fms lead=%.1fms"
            .format(java.util.Locale.US, avgMs, waitMaxNs / 1e6, leadNs / 1e6)
        scheduled = 0
        shownNow = 0
        waitSumNs = 0L
        waitMaxNs = 0L
        return text
    }

    companion object {
        /** About a quarter of a 60 Hz refresh: room for the display service to take the buffer before the refresh. */
        const val DEFAULT_LEAD_NS = 4_000_000L

        /** Farther ahead than this and the frame is shown now, which also keeps the pacing from queueing without limit. */
        const val MAX_AHEAD_PERIODS = 3L
    }
}
