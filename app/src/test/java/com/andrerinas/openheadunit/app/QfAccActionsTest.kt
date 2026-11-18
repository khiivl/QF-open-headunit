package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.aap.AapService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The QF head units announce the ignition with com.qf.action.ACC_ON / ACC_OFF (what wRight listens for on the K706). */
class QfAccActionsTest {
    private val manifest get() = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `ACC on from a QF unit is a boot action`() {
        assertTrue("com.qf.action.ACC_ON" in BootCompleteReceiver.BOOT_ACTIONS)
        assertTrue(manifest.contains("<action android:name=\"com.qf.action.ACC_ON\" />"))
    }

    @Test
    fun `ACC off from a QF unit tears the session down`() {
        assertTrue("com.qf.action.ACC_OFF" in AapService.ACC_OFF_ACTIONS)
    }

    @Test
    fun `ACC off is never treated as a wake`() {
        assertFalse(AapService.ACC_OFF_ACTIONS.any { it in BootCompleteReceiver.BOOT_ACTIONS })
        assertFalse(manifest.contains("com.qf.action.ACC_OFF"))
    }
}
