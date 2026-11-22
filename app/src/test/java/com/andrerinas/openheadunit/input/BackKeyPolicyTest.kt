package com.andrerinas.openheadunit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackKeyPolicyTest {
    private lateinit var policy: BackKeyPolicy

    @Before
    fun setUp() {
        policy = BackKeyPolicy(500L)
    }

    @Test
    fun shortPressTriggersForwardBack() {
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyDown(1000L, isRepeat = false))
        assertTrue(policy.isTracking)
        assertFalse(policy.isLongPressTriggered)

        // Release at 1200ms (duration 200ms < 500ms)
        assertEquals(BackKeyPolicy.Action.FORWARD_BACK, policy.onKeyUp(1200L))
        assertFalse(policy.isTracking)
        assertFalse(policy.isLongPressTriggered)
    }

    @Test
    fun longPressViaTimerTriggersShowExitDialog() {
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyDown(1000L, isRepeat = false))

        // Timer fires after 500ms
        assertEquals(BackKeyPolicy.Action.SHOW_EXIT_DIALOG, policy.onTimerExpired())
        assertTrue(policy.isLongPressTriggered)

        // Up at 1600ms is consumed
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyUp(1600L))
        assertFalse(policy.isTracking)
        assertFalse(policy.isLongPressTriggered)
    }

    @Test
    fun longPressViaKeyRepeatTriggersShowExitDialog() {
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyDown(1000L, isRepeat = false))

        // Repeat before 500ms does nothing
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyDown(1300L, isRepeat = true))
        assertFalse(policy.isLongPressTriggered)

        // Repeat at or past 500ms triggers SHOW_EXIT_DIALOG
        assertEquals(BackKeyPolicy.Action.SHOW_EXIT_DIALOG, policy.onKeyDown(1550L, isRepeat = true))
        assertTrue(policy.isLongPressTriggered)

        // Subsequent repeat does not re-trigger
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyDown(1600L, isRepeat = true))

        // Release is consumed
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyUp(1700L))
        assertFalse(policy.isTracking)
    }

    @Test
    fun longPressWithoutTimerHeldPastTimeoutTriggersShowExitDialogOnUp() {
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyDown(1000L, isRepeat = false))

        // Release past timeout without timer firing triggers SHOW_EXIT_DIALOG
        assertEquals(BackKeyPolicy.Action.SHOW_EXIT_DIALOG, policy.onKeyUp(1600L))
        assertFalse(policy.isTracking)
    }

    @Test
    fun longPressFlagTriggersShowExitDialogImmediately() {
        assertEquals(BackKeyPolicy.Action.SHOW_EXIT_DIALOG, policy.onKeyDown(1000L, isRepeat = false, isLongPress = true))
        assertTrue(policy.isTracking)
        assertTrue(policy.isLongPressTriggered)

        // Release is consumed
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyUp(1010L))
        assertFalse(policy.isTracking)
    }

    @Test
    fun untrackedKeyUpReturnsNone() {
        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyUp(1000L))
    }

    @Test
    fun timerExpiredWithoutTrackingReturnsNone() {
        assertEquals(BackKeyPolicy.Action.NONE, policy.onTimerExpired())
    }

    @Test
    fun resetClearsState() {
        policy.onKeyDown(1000L, isRepeat = false)
        assertTrue(policy.isTracking)

        policy.reset()
        assertFalse(policy.isTracking)
        assertFalse(policy.isLongPressTriggered)

        assertEquals(BackKeyPolicy.Action.NONE, policy.onKeyUp(1200L))
    }
}
