package com.andrerinas.openheadunit.input

import kotlin.math.roundToLong

/**
 * Turns a touchscreen's move samples into moves on a steady grid (60 or 120 Hz) for the phone.
 *
 * A QF unit's panel reports about 95 times a second and Android Auto draws 60 frames a second, so sending every sample
 * gave some frames one move and others two: measured during a constant-speed drag, the map stepped 4 and 8 pixels in
 * turn, and a steady 5 to 6 when fed at 60 Hz.
 *
 * The position for each grid time is read off the finger's path [delayMs] earlier, between the two samples around it,
 * so there is always a later sample to interpolate towards. Presses and releases are not resampled: [flush] hands out
 * the newest position first, so a lift follows the finger's last place. Times are in milliseconds on one clock.
 */
class TouchMoveResampler {

    data class Move(val at: Long, val pointers: List<RemoteTouchPolicy.Pointer>)

    private val samples = ArrayList<Move>()
    private var sent: Move? = null

    /** The panel's report period as seen in the samples; starts at a QF unit's stock one and settles within a drag. */
    private var periodSeenMs = STOCK_PERIOD_MS

    /** How far behind the grid time the path is read: one panel report plus the time a sample takes to arrive. */
    val delayMs: Double get() = (periodSeenMs + ARRIVAL_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

    /** Forgets the path and starts it again from an event that has just been sent as it was. */
    fun anchor(at: Long, pointers: List<RemoteTouchPolicy.Pointer>) {
        samples.clear()
        val move = Move(at, pointers)
        samples.add(move)
        sent = move
    }

    fun add(at: Long, pointers: List<RemoteTouchPolicy.Pointer>) {
        val last = samples.lastOrNull()
        // Different fingers than before cannot be interpolated between.
        if (last != null && last.pointers.map { it.id } != pointers.map { it.id }) samples.clear()
        samples.lastOrNull()?.let {
            val gap = (at - it.at).toDouble()
            // Longer gaps are a resting finger, not the panel's rate.
            if (gap > 0 && gap <= LONGEST_PERIOD_MS) periodSeenMs += (gap - periodSeenMs) * 0.1
        }
        samples.add(Move(at, pointers))
        if (samples.size > KEPT) samples.removeAt(0)
    }

    /** The move to send for the grid time [now], or null when the finger is where the phone already has it. */
    fun tick(now: Double): Move? {
        val newest = samples.lastOrNull() ?: return null
        val target = maxOf(now - delayMs, (sent?.at ?: Long.MIN_VALUE).toDouble())
        val move = if (target >= newest.at) newest else {
            val after = samples.indexOfFirst { it.at > target }
            if (after <= 0) samples[0] else between(samples[after - 1], samples[after], target)
        }
        return hand(move)
    }

    /** True once the newest sample has been sent: nothing is left for the grid to do until another arrives. */
    fun isSettled(): Boolean = samples.lastOrNull()?.pointers == sent?.pointers

    /** The newest position if the phone has not had it yet. Called before anything that is not a move. */
    fun flush(): Move? = samples.lastOrNull()?.let { hand(it) }

    private fun hand(move: Move): Move? {
        if (move.pointers == sent?.pointers) return null
        sent = move
        return move
    }

    private fun between(a: Move, b: Move, target: Double): Move {
        val f = (((target - a.at) / (b.at - a.at)).coerceIn(0.0, 1.0)).toFloat()
        return Move(
            target.roundToLong(),
            a.pointers.zip(b.pointers) { p, q ->
                RemoteTouchPolicy.Pointer(p.id, p.x + (q.x - p.x) * f, p.y + (q.y - p.y) * f)
            }
        )
    }

    companion object {
        private const val STOCK_PERIOD_MS = 10.5
        private const val LONGEST_PERIOD_MS = 20.0
        private const val ARRIVAL_MS = 2.5
        private const val MIN_DELAY_MS = 7.0
        private const val MAX_DELAY_MS = 13.0

        private const val KEPT = 16
    }
}
