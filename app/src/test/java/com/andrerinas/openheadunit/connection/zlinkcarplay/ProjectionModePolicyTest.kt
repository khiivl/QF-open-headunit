package com.andrerinas.openheadunit.connection.zlinkcarplay

import com.andrerinas.openheadunit.utils.Settings.ProjectionAvailability
import com.andrerinas.openheadunit.utils.Settings.ProjectionMode
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionModePolicyTest {
    private val stop = "STOP"

    @Test fun androidAutoModeChangesNothing() {
        assertTrue(ProjectionModePolicy.androidAutoEnabled(ProjectionMode.ANDROID_AUTO))
        assertTrue(ProjectionModePolicy.androidAutoEnabled(null))
        assertFalse(ProjectionModePolicy.carPlayEnabled(ProjectionMode.ANDROID_AUTO))
        assertFalse(ProjectionModePolicy.carPlayEnabled(null))
        for (a in listOf(null, "START", "BT", stop)) {
            assertTrue(ProjectionModePolicy.androidAutoServiceMayHandle(ProjectionMode.ANDROID_AUTO, a, stop))
            assertTrue(ProjectionModePolicy.androidAutoServiceMayHandle(null, a, stop))
        }
    }

    @Test fun carPlayModeBlocksEverythingButStop() {
        assertFalse(ProjectionModePolicy.androidAutoEnabled(ProjectionMode.CARPLAY_ZLINK))
        assertTrue(ProjectionModePolicy.carPlayEnabled(ProjectionMode.CARPLAY_ZLINK))
        for (a in listOf(null, "START", "BT")) {
            assertFalse(ProjectionModePolicy.androidAutoServiceMayHandle(ProjectionMode.CARPLAY_ZLINK, a, stop))
        }
        assertTrue(ProjectionModePolicy.androidAutoServiceMayHandle(ProjectionMode.CARPLAY_ZLINK, stop, stop))
    }

    @Test fun unknownStoredValueFallsBackToAndroidAuto() {
        assertEquals(ProjectionMode.ANDROID_AUTO, ProjectionMode.fromInt(7))
        assertEquals(ProjectionMode.CARPLAY_ZLINK, ProjectionMode.fromInt(1))
    }

    @Test fun bothAllowsEveryModeAndNeverForcesASwitch() {
        for (m in ProjectionMode.values()) {
            assertTrue(ProjectionModePolicy.allows(ProjectionAvailability.BOTH, m))
            assertNull(ProjectionModePolicy.mustSwitchTo(ProjectionAvailability.BOTH, m))
        }
    }

    @Test fun aSingleProjectionAllowsOnlyItsOwnMode() {
        assertTrue(ProjectionModePolicy.allows(ProjectionAvailability.ANDROID_AUTO_ONLY, ProjectionMode.ANDROID_AUTO))
        assertFalse(ProjectionModePolicy.allows(ProjectionAvailability.ANDROID_AUTO_ONLY, ProjectionMode.CARPLAY_ZLINK))
        assertTrue(ProjectionModePolicy.allows(ProjectionAvailability.CARPLAY_ONLY, ProjectionMode.CARPLAY_ZLINK))
        assertFalse(ProjectionModePolicy.allows(ProjectionAvailability.CARPLAY_ONLY, ProjectionMode.ANDROID_AUTO))
    }

    @Test fun theRunningModeIsSwitchedOnlyWhenItIsTheOneTurnedOff() {
        assertEquals(ProjectionMode.ANDROID_AUTO,
            ProjectionModePolicy.mustSwitchTo(ProjectionAvailability.ANDROID_AUTO_ONLY, ProjectionMode.CARPLAY_ZLINK))
        assertEquals(ProjectionMode.CARPLAY_ZLINK,
            ProjectionModePolicy.mustSwitchTo(ProjectionAvailability.CARPLAY_ONLY, ProjectionMode.ANDROID_AUTO))
        assertNull(ProjectionModePolicy.mustSwitchTo(ProjectionAvailability.ANDROID_AUTO_ONLY, ProjectionMode.ANDROID_AUTO))
        assertNull(ProjectionModePolicy.mustSwitchTo(ProjectionAvailability.CARPLAY_ONLY, ProjectionMode.CARPLAY_ZLINK))
    }
}
