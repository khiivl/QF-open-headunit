package com.andrerinas.openheadunit.decoder.video

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Tells a [VsyncPacer] where the display's refreshes fall.
 *
 * A Choreographer frame callback carries the time of the refresh it was delivered for, on the same `System.nanoTime()`
 * clock that release timestamps use. A few of them in a row give the period (the display's refresh rate is not always
 * exactly what it says), and one every half second keeps the phase from drifting. Callbacks have to be posted from a thread
 * with a Looper, so this runs on the main one; nothing here touches the decoder's own threads.
 *
 * It costs the main thread a callback per refresh for the first moment and one every half second after, and it stops with
 * the decoder.
 */
class VsyncClock(private val pacer: VsyncPacer) {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private var samples = 0
    private var firstNs = 0L
    private var periodNs = FALLBACK_PERIOD_NS

    private val callback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }
    private val resync = Runnable { if (running) Choreographer.getInstance().postFrameCallback(callback) }

    fun start() {
        running = true
        samples = 0
        handler.post { if (running) Choreographer.getInstance().postFrameCallback(callback) }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(resync)
        handler.post { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running) return
        if (samples == 0) firstNs = frameTimeNanos
        samples++
        if (samples < CALIBRATION_FRAMES) {
            // Still measuring: ask for the next refresh too.
            Choreographer.getInstance().postFrameCallback(callback)
            return
        }
        if (samples == CALIBRATION_FRAMES) {
            val measured = (frameTimeNanos - firstNs) / (CALIBRATION_FRAMES - 1)
            periodNs = if (measured in MIN_PERIOD_NS..MAX_PERIOD_NS) measured else FALLBACK_PERIOD_NS
            AppLog.i("VsyncClock: the display refreshes every ${"%.2f".format(periodNs / 1e6)} ms (measured over $CALIBRATION_FRAMES refreshes)")
        }
        pacer.setBeat(frameTimeNanos, periodNs)
        // From here one callback every so often keeps the phase true.
        samples = CALIBRATION_FRAMES + 1
        handler.postDelayed(resync, RESYNC_MS)
    }

    private companion object {
        const val CALIBRATION_FRAMES = 8
        const val RESYNC_MS = 500L
        const val FALLBACK_PERIOD_NS = 16_666_667L
        const val MIN_PERIOD_NS = 8_000_000L
        const val MAX_PERIOD_NS = 40_000_000L
    }
}
