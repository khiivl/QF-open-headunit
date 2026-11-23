package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyHotspotPolicyTest {

    private fun start(
        setting: Boolean = true, zlink: Boolean = true, apOn: Boolean = false,
        accOff: Boolean = false, screenOff: Boolean = false, asked: Boolean = false
    ) = EarlyHotspotPolicy.shouldStart(setting, zlink, apOn, accOff, screenOff, asked)

    @Test fun `a zLink unit with no hotspot asks at process start`() = assertTrue(start())

    @Test fun `only a zLink unit asks early`() = assertFalse(start(zlink = false))

    @Test fun `the setting off means never`() = assertFalse(start(setting = false))

    @Test fun `a hotspot that is already up is left alone`() = assertFalse(start(apOn = true))

    @Test fun `a car that is off or a screen that is off does not start it`() {
        assertFalse(start(accOff = true))
        assertFalse(start(screenOff = true))
    }

    @Test fun `it asks once`() = assertFalse(start(asked = true))
}
