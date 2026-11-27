package com.andrerinas.openheadunit.connection

import android.view.KeyEvent

/**
 * Whether a play or pause press is the same button press arriving again by another route.
 *
 * On a QF unit one press of the wheel's button reaches the app twice: as a normal key (to the projection screen or the
 * media session) and through the unit's key log. The two can be further apart than [KeyDebouncePolicy]'s window, and can
 * carry different codes. A second toggle that gets through starts the music again, so the pause seems to be ignored.
 *
 * Only a press from a different route is an echo, so two real presses by one route are both passed on. An explicit play
 * followed by an explicit pause is never one: neither undoes a command by being sent twice.
 */
object PlayPauseEchoPolicy {

    const val WINDOW_MS = 1_500L

    /** The last play or pause press that was sent on. */
    data class Press(val code: Int, val source: String, val at: Long)

    fun covers(code: Int): Boolean =
        code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE

    fun isEcho(last: Press?, code: Int, source: String, now: Long): Boolean {
        if (last == null || !covers(code)) return false
        if (source == last.source || now - last.at >= WINDOW_MS) return false
        return code == last.code ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            last.code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }
}
