package com.andrerinas.openheadunit.connection.zlinkcarplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlinkScreenPolicyTest {

    @Test
    fun hideCommandsTargetAllUiActivitiesAndDoNotTouchActivation() {
        val cmds = ZlinkScreenPolicy.buildHideCommands("com.zjinnova.zlink")
        assertEquals(ZlinkScreenPolicy.UI_ACTIVITIES.size, cmds.size)

        for (cmd in cmds) {
            assertTrue(cmd.startsWith("pm disable com.zjinnova.zlink/"))
            assertFalse("ActivationActivity must never be disabled", cmd.contains("ActivationActivity"))
            assertFalse("CheckPermissions must never be disabled", cmd.contains("CheckPermissions"))
        }

        assertTrue(cmds.any { it.endsWith("MainActivity") })
        assertTrue(cmds.any { it.endsWith("CarPlayActivity") })
        assertTrue(cmds.any { it.endsWith("AutoActivity") })
    }

    @Test
    fun restoreCommandsTargetAllUiActivities() {
        val cmds = ZlinkScreenPolicy.buildRestoreCommands("com.zjinnova.zlink5")
        assertEquals(ZlinkScreenPolicy.UI_ACTIVITIES.size, cmds.size)

        for (cmd in cmds) {
            assertTrue(cmd.startsWith("pm enable com.zjinnova.zlink5/"))
            assertFalse(cmd.contains("ActivationActivity"))
        }

        assertTrue(cmds.any { it.endsWith("MainActivity") })
        assertTrue(cmds.any { it.endsWith("CarPlayActivity") })
    }

    @Test
    fun combinedCommandsAreJoinedWithSemicolon() {
        val hide = ZlinkScreenPolicy.buildHideCommand("com.zjinnova.zlink")
        assertTrue(hide.contains(" ; pm disable com.zjinnova.zlink/"))

        val restore = ZlinkScreenPolicy.buildRestoreCommand("com.zjinnova.zlink")
        assertTrue(restore.contains(" ; pm enable com.zjinnova.zlink/"))
    }
}
