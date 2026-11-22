package com.andrerinas.openheadunit.input

/**
 * Pure policy governing the physical BACK button on head units.
 *
 * A short press (< [longPressTimeoutMs]) forwards a back press into the active projection
 * (Android Auto or CarPlay), allowing the user to navigate back within apps and submenus.
 * A long press (held down >= [longPressTimeoutMs]) triggers the exit / disconnection dialog.
 */
class BackKeyPolicy(
    val longPressTimeoutMs: Long = DEFAULT_LONG_PRESS_TIMEOUT_MS
) {
    enum class Action {
        NONE,
        FORWARD_BACK,
        SHOW_EXIT_DIALOG
    }

    private var pressStartTimeMs: Long = 0L
    private var tracking = false
    private var longPressTriggered = false

    val isTracking: Boolean
        get() = tracking

    val isLongPressTriggered: Boolean
        get() = longPressTriggered

    fun onKeyDown(nowMs: Long, isRepeat: Boolean, isLongPress: Boolean = false): Action {
        if (!tracking) {
            tracking = true
            pressStartTimeMs = nowMs
            longPressTriggered = false
            if (isLongPress) {
                longPressTriggered = true
                return Action.SHOW_EXIT_DIALOG
            }
            return Action.NONE
        }

        if (isLongPress && !longPressTriggered) {
            longPressTriggered = true
            return Action.SHOW_EXIT_DIALOG
        }

        // Key is repeating while held down
        if (!longPressTriggered && (nowMs - pressStartTimeMs) >= longPressTimeoutMs) {
            longPressTriggered = true
            return Action.SHOW_EXIT_DIALOG
        }

        return Action.NONE
    }

    fun onTimerExpired(): Action {
        if (tracking && !longPressTriggered) {
            longPressTriggered = true
            return Action.SHOW_EXIT_DIALOG
        }
        return Action.NONE
    }

    fun onKeyUp(nowMs: Long): Action {
        if (!tracking) return Action.NONE
        tracking = false

        if (longPressTriggered) {
            longPressTriggered = false
            return Action.NONE
        }

        return if ((nowMs - pressStartTimeMs) < longPressTimeoutMs) {
            Action.FORWARD_BACK
        } else {
            Action.SHOW_EXIT_DIALOG
        }
    }

    fun reset() {
        tracking = false
        longPressTriggered = false
        pressStartTimeMs = 0L
    }

    companion object {
        const val DEFAULT_LONG_PRESS_TIMEOUT_MS = 500L
    }
}
