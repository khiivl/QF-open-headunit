package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import com.andrerinas.openheadunit.input.MediaSeekHold.Kind
import com.andrerinas.openheadunit.input.MediaSeekHold.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSeekHoldTest {

    private val next = KeyEvent.KEYCODE_MEDIA_NEXT
    private val previous = KeyEvent.KEYCODE_MEDIA_PREVIOUS
    private val ff = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    private val rew = KeyEvent.KEYCODE_MEDIA_REWIND

    @Test fun `a short press is sent when the key goes up`() {
        val hold = MediaSeekHold()
        assertNull(hold.onKey(next, 0, 1_000))
        assertEquals(Step(Kind.PRESS, next), hold.onKey(next, 1, 1_006))
    }

    @Test fun `a hold starts the seek at the first repeat, repeats it with every repeat, and the up ends it`() {
        val hold = MediaSeekHold()
        assertNull(hold.onKey(next, 0, 1_000))
        assertEquals(Step(Kind.SEEK_DOWN, ff), hold.onKey(next, 0, 1_400))
        assertTrue(hold.isHolding())
        assertEquals(Step(Kind.SEEK_REPEAT, ff), hold.onKey(next, 0, 1_800))
        assertEquals(Step(Kind.SEEK_REPEAT, ff), hold.onKey(next, 0, 2_200))
        assertEquals(Step(Kind.SEEK_UP, ff), hold.onKey(next, 1, 2_300))
        assertFalse(hold.isHolding())
    }

    @Test fun `previous seeks backwards`() {
        val hold = MediaSeekHold()
        hold.onKey(previous, 0, 0)
        assertEquals(Step(Kind.SEEK_DOWN, rew), hold.onKey(previous, 0, 400))
        assertEquals(Step(Kind.SEEK_REPEAT, rew), hold.onKey(previous, 0, 800))
        assertEquals(Step(Kind.SEEK_UP, rew), hold.onKey(previous, 1, 900))
    }

    @Test fun `two short presses in a row are two presses`() {
        val hold = MediaSeekHold()
        hold.onKey(next, 0, 0)
        assertEquals(Step(Kind.PRESS, next), hold.onKey(next, 1, 5))
        hold.onKey(next, 0, 300)
        assertEquals(Step(Kind.PRESS, next), hold.onKey(next, 1, 306))
    }

    @Test fun `the same down seen twice is not a repeat`() {
        val hold = MediaSeekHold()
        hold.onKey(next, 0, 1_000)
        assertNull(hold.onKey(next, 0, 1_004))
        assertFalse(hold.isHolding())
        assertEquals(Step(Kind.PRESS, next), hold.onKey(next, 1, 1_010))
    }

    @Test fun `a repeat seen twice during a hold is sent once`() {
        val hold = MediaSeekHold()
        hold.onKey(next, 0, 0)
        assertEquals(Step(Kind.SEEK_DOWN, ff), hold.onKey(next, 0, 400))
        assertNull(hold.onKey(next, 0, 404))
        assertEquals(Step(Kind.SEEK_REPEAT, ff), hold.onKey(next, 0, 800))
    }

    @Test fun `an up seen twice sends once`() {
        val hold = MediaSeekHold()
        hold.onKey(next, 0, 0)
        hold.onKey(next, 0, 400)
        assertEquals(Step(Kind.SEEK_UP, ff), hold.onKey(next, 1, 800))
        assertNull(hold.onKey(next, 1, 804))
    }

    @Test fun `next and previous do not disturb each other`() {
        val hold = MediaSeekHold()
        hold.onKey(next, 0, 0)
        hold.onKey(previous, 0, 100)
        assertEquals(Step(Kind.SEEK_DOWN, ff), hold.onKey(next, 0, 400))
        assertEquals(Step(Kind.PRESS, previous), hold.onKey(previous, 1, 450))
    }

    @Test fun `a hold whose up was lost is ended by the watchdog`() {
        val hold = MediaSeekHold()
        hold.onKey(next, 0, 0)
        hold.onKey(next, 0, 400)
        assertTrue(hold.expire(400 + MediaSeekHold.WATCHDOG_MS - 1).isEmpty())
        assertEquals(listOf(Step(Kind.SEEK_UP, ff)), hold.expire(400 + MediaSeekHold.WATCHDOG_MS))
        assertFalse(hold.isHolding())
        assertTrue(hold.expire(10_000).isEmpty())
    }

    @Test fun `a press that never got its up does not turn a later press into a hold`() {
        val hold = MediaSeekHold()
        hold.onKey(next, 0, 0)
        // The up was lost; a new press long after is a new press, not the second down of a hold.
        assertNull(hold.onKey(next, 0, 5_000))
        assertFalse(hold.isHolding())
        assertEquals(Step(Kind.PRESS, next), hold.onKey(next, 1, 5_006))
    }

    @Test fun `other keys are not seek keys`() {
        val hold = MediaSeekHold()
        assertNull(hold.onKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0, 0))
        assertFalse(MediaSeekHold.isSeekKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(MediaSeekHold.isSeekKey(next))
    }
}
