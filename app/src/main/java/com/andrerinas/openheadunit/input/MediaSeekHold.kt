package com.andrerinas.openheadunit.input

import android.view.KeyEvent

/**
 * Tells a short press of the unit's next and previous buttons from a long one, so a hold can seek.
 *
 * The unit logs a key down and a key up for every press, and while a button is held it repeats the down about every
 * 0.4 s before the single up (measured: holding next gave downs at 0.0, 0.4, 0.8 ... s and one up at the end). So:
 *  - a down followed by an up is a short press, sent as the key itself when the up arrives;
 *  - a second down before the up means the button is held: seeking starts (fast forward for next, rewind for previous),
 *    and every further repeat of the unit's down is sent again, as a real held key keeps sending its down. A media app
 *    seeks once per key event it gets, so one event gave one seek and the repeats give a seek that goes on while held;
 *  - the up ends the hold.
 * The seek keys are sent as a long press.
 *
 * Android Auto does not seek by itself; it hands the key to the phone's media app. Measured on the phone: VLC seeks on
 * the fast forward and rewind keys sent this way, Spotify ignores them (it also ignores them as a long press of next or
 * previous), and in VLC a long press of next or previous only skips.
 *
 * A short press is therefore sent on release, a few milliseconds after it was pressed. A lost up cannot leave the phone
 * seeking for good: [expire] ends a hold that has not repeated for [WATCHDOG_MS].
 *
 * Pure state, no clocks and no sending: the caller passes the time and does what the returned [Step] says.
 */
class MediaSeekHold {

    enum class Kind {
        /** A short press: key down then key up of [Step.key]. */
        PRESS,

        /** Start of a hold: key down of [Step.key], the seek key, marked as a long press. */
        SEEK_DOWN,

        /** The hold goes on: another key down of the seek key, as a held key repeats. */
        SEEK_REPEAT,

        /** End of a hold: key up of [Step.key], the seek key. */
        SEEK_UP
    }

    data class Step(val kind: Kind, val key: Int)

    private class State(var holding: Boolean, var lastMs: Long)

    private val states = HashMap<Int, State>()

    /** What to send for a key line of [code] and [action] ("0" down, "1" up) at [nowMs], or null for nothing yet. */
    fun onKey(code: Int, action: Int, nowMs: Long): Step? {
        val seek = seekKey(code) ?: return null
        val state = states[code]
        if (action == 0) {
            if (state == null || nowMs - state.lastMs > MAX_REPEAT_GAP_MS) {
                // A new press (or one whose earlier lines were lost).
                states[code] = State(holding = false, lastMs = nowMs)
                return null
            }
            // The same line seen twice (two readers) is not a repeat: the unit's repeats are 0.4 s apart.
            if (nowMs - state.lastMs < MIN_REPEAT_GAP_MS) return null
            state.lastMs = nowMs
            val first = !state.holding
            state.holding = true
            return Step(if (first) Kind.SEEK_DOWN else Kind.SEEK_REPEAT, seek)
        }
        val ended = states.remove(code) ?: return null
        return if (ended.holding) Step(Kind.SEEK_UP, seek) else Step(Kind.PRESS, code)
    }

    /** Ends every hold that has not repeated for [WATCHDOG_MS] and forgets presses that never got their up. */
    fun expire(nowMs: Long): List<Step> {
        val out = ArrayList<Step>()
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val (code, state) = it.next()
            if (nowMs - state.lastMs < WATCHDOG_MS) continue
            if (state.holding) out += Step(Kind.SEEK_UP, seekKey(code)!!)
            it.remove()
        }
        return out
    }

    /** True while some hold is going, which is when [expire] has to be called. */
    fun isHolding(): Boolean = states.values.any { it.holding }

    companion object {
        /** The longest pause between two downs of one hold; the unit repeats about every 400 ms. */
        const val MAX_REPEAT_GAP_MS = 1_000L

        /** Downs closer than this are the same line twice, not a repeat. */
        const val MIN_REPEAT_GAP_MS = 150L

        /** A hold that has not repeated for this long has lost its up. */
        const val WATCHDOG_MS = 1_200L

        /** The key a held [code] seeks with: fast forward for next, rewind for previous, null for any other key. */
        fun seekKey(code: Int): Int? = when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_REWIND
            else -> null
        }

        fun isSeekKey(code: Int): Boolean = seekKey(code) != null
    }
}
