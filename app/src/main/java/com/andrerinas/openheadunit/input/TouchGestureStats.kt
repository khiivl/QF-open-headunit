package com.andrerinas.openheadunit.input

/**
 * What one finger gesture looked like on its way to the phone, for the log.
 *
 * Android Auto works out scroll and fling speed on the phone from the touch events it is sent, so the spacing and the
 * timing of those events decide how a scroll feels. This keeps the numbers that say whether they were right: how many
 * samples the gesture had, how many of them came from the batch history (the touchscreen reports faster than the display
 * refreshes), how evenly spaced their real times were, and how long after the touch the app got to handle each one.
 *
 * All times are milliseconds on the clock the protocol timestamps use. Not thread-safe: the UI thread is the only caller.
 */
class TouchGestureStats {

    private val eventTimes = LongArray(CAPACITY)
    private val delays = LongArray(CAPACITY)
    private var count = 0
    private var historical = 0

    /** One sample: when the finger really was there, and when the app handled it. */
    fun add(eventElapsedMs: Long, handledAtMs: Long, fromHistory: Boolean) {
        if (count >= CAPACITY) return
        eventTimes[count] = eventElapsedMs
        delays[count] = (handledAtMs - eventElapsedMs).coerceAtLeast(0L)
        count++
        if (fromHistory) historical++
    }

    /** A log line for the gesture, or null when it was too short to say anything (a tap). */
    fun report(): String? {
        if (count < MIN_SAMPLES) return null
        val gaps = LongArray(count - 1) { eventTimes[it + 1] - eventTimes[it] }.also { it.sort() }
        val late = delays.copyOf(count).also { it.sort() }
        val spanMs = eventTimes[count - 1] - eventTimes[0]
        return "TouchGesture: $count samples ($historical from batch history) over ${spanMs}ms, " +
            "spacing mean=${"%.1f".format(java.util.Locale.US, gaps.average())}ms " +
            "p95=${percentile(gaps, 95)}ms max=${gaps.last()}ms, " +
            "handled ${"%.1f".format(java.util.Locale.US, late.average())}ms after the touch " +
            "(p95=${percentile(late, 95)}ms)"
    }

    fun reset() {
        count = 0
        historical = 0
    }

    private fun percentile(sorted: LongArray, p: Int): Long =
        sorted[((sorted.size - 1) * p / 100).coerceIn(0, sorted.size - 1)]

    private companion object {
        const val CAPACITY = 1024
        const val MIN_SAMPLES = 6
    }
}
