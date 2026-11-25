package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.utils.Settings.ProjectionAvailability
import com.andrerinas.openheadunit.utils.Settings.ProjectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodosHomePolicyTest {

    @Test fun carPlayIsOffUntilItsPathIsProven() {
        assertFalse(CodosHomePolicy.CARPLAY_PATH_READY)
    }

    @Test fun carPlayTileNeverShowsWithoutAWorkingPath() {
        val t = CodosHomePolicy.tiles(ProjectionAvailability.BOTH, ProjectionMode.ANDROID_AUTO, carPlayPathReady = false)
        assertTrue(t.androidAutoVisible)
        assertFalse(t.carPlayVisible)
        assertTrue(t.androidAutoSelected)
        assertFalse(t.carPlaySelected)
    }

    @Test fun bothTilesWhenThePathWorksAndTheSelectedOneIsMarked() {
        val aa = CodosHomePolicy.tiles(ProjectionAvailability.BOTH, ProjectionMode.ANDROID_AUTO, true)
        assertTrue(aa.androidAutoVisible && aa.carPlayVisible && aa.androidAutoSelected && !aa.carPlaySelected)
        val cp = CodosHomePolicy.tiles(ProjectionAvailability.BOTH, ProjectionMode.CARPLAY_ZLINK, true)
        assertTrue(cp.androidAutoVisible && cp.carPlayVisible && !cp.androidAutoSelected && cp.carPlaySelected)
    }

    @Test fun availabilityStillDecides() {
        val aaOnly = CodosHomePolicy.tiles(ProjectionAvailability.ANDROID_AUTO_ONLY, ProjectionMode.ANDROID_AUTO, true)
        assertTrue(aaOnly.androidAutoVisible)
        assertFalse(aaOnly.carPlayVisible)
        val cpOnly = CodosHomePolicy.tiles(ProjectionAvailability.CARPLAY_ONLY, ProjectionMode.CARPLAY_ZLINK, true)
        assertFalse(cpOnly.androidAutoVisible)
        assertTrue(cpOnly.carPlayVisible && cpOnly.carPlaySelected)
    }

    @Test fun sevenQuickTapsOpenTheDeveloperSection() {
        var count = 0
        var last = 0L
        var opened = false
        for (i in 1..7) {
            val t = CodosHomePolicy.nextTaps(count, last, now(i))
            count = t.count
            last = now(i)
            opened = t.openDeveloper
            if (i < 7) assertFalse(opened)
        }
        assertTrue(opened)
    }

    @Test fun aPauseStartsTheCountAgain() {
        var t = CodosHomePolicy.nextTaps(0, 0L, 1_000L)
        t = CodosHomePolicy.nextTaps(t.count, 1_000L, 1_500L)
        assertEquals(2, t.count)
        t = CodosHomePolicy.nextTaps(t.count, 1_500L, 1_500L + CodosHomePolicy.TAP_WINDOW_MS + 1)
        assertEquals(1, t.count)
        assertFalse(t.openDeveloper)
    }

    private fun now(i: Int): Long = 10_000L + i * 200L
}
