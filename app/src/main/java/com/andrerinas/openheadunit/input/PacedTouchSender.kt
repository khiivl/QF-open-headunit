package com.andrerinas.openheadunit.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Sends one finger's moves on a steady grid through [TouchMoveResampler], for a link that takes plain press, move and
 * release (CarPlay's). A rate of 0 passes every sample straight on. Main thread only; times are on the uptime clock,
 * as MotionEvent's are.
 */
class PacedTouchSender(private val emit: (RemoteTouchPolicy.Pointer, Boolean) -> Unit) {

    /** False sends the newest position on each grid time as it is: sooner than interpolating, less even. */
    private var interpolate = true

    private val handler = Handler(Looper.getMainLooper())
    private val resampler = TouchMoveResampler()
    private var rate = 0
    private var gridAt = 0.0
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            val next = if (interpolate) resampler.tick(gridAt) else resampler.flush()
            next?.let { emit(it.pointers[0], true) }
            if (resampler.isSettled()) {
                running = false
                return
            }
            // Grid times stay one period apart however late the handler runs; missed ones are skipped.
            val now = SystemClock.uptimeMillis().toDouble()
            do { gridAt += 1000.0 / rate } while (gridAt <= now)
            handler.postDelayed(this, Math.round(gridAt - now))
        }
    }

    fun press(at: Long, pointer: RemoteTouchPolicy.Pointer, sendRate: Int, interpolated: Boolean) {
        stop()
        interpolate = interpolated
        rate = sendRate
        emit(pointer, true)
        resampler.anchor(at, listOf(pointer))
    }

    fun move(at: Long, pointer: RemoteTouchPolicy.Pointer) {
        if (rate <= 0) {
            emit(pointer, true)
            return
        }
        resampler.add(at, listOf(pointer))
        if (!running) {
            running = true
            gridAt = SystemClock.uptimeMillis().toDouble()
            handler.post(tick)
        }
    }

    /** Lifts the finger, after any position still waiting for its grid time. */
    fun release(at: Long, pointer: RemoteTouchPolicy.Pointer) {
        stop()
        if (rate > 0) resampler.flush()?.let { emit(it.pointers[0], true) }
        emit(pointer, false)
        resampler.anchor(at, listOf(pointer))
    }

    fun stop() {
        handler.removeCallbacks(tick)
        running = false
    }
}
