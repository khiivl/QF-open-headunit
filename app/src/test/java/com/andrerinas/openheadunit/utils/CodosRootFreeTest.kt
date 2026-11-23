package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CoD OS flavor has no root: no `su`, no root-only daemon control, no uid 0 in what it asks a
 * shell to run. The other flavors keep the upstream command lists. See cod/ROOT_TO_SYSTEM.md.
 */
class CodosRootFreeTest {

    private val zlink = OemAppManager.KNOWN_TARGETS.first { it.id == "zlink" }

    private fun status(target: OemAppManager.OemAppTarget = zlink) = OemAppManager.OemAppStatus(
        target = target,
        installedPackage = target.packageCandidates.first(),
        isInstalled = true,
        isEnabled = true,
        isRunning = false,
    )

    @Test
    fun codosDisableKeepsPackageControlAndDropsDaemonKills() {
        val cmds = OemAppManager.getDisableCommands(null, status(), codos = true)
        assertEquals(
            listOf("pm disable-user --user 0 com.zjinnova.zlink5", "am force-stop com.zjinnova.zlink5"),
            cmds
        )
    }

    @Test
    fun upstreamDisableStillKillsDaemons() {
        val cmds = OemAppManager.getDisableCommands(null, status(), codos = false)
        assertTrue(cmds.any { it.startsWith("pkill -f ") })
        assertEquals(2 + zlink.daemonNames.size, cmds.size)
    }

    @Test
    fun codosRestoreDoesNotStartRootDaemons() {
        val cmds = OemAppManager.getRestoreCommands(null, status(), codos = true)
        assertEquals(listOf("pm enable --user 0 com.zjinnova.zlink5"), cmds)
    }

    @Test
    fun upstreamRestoreStillStartsDaemonScripts() {
        val cmds = OemAppManager.getRestoreCommands(null, status(), codos = false)
        assertTrue(cmds.any { it.contains("setsid /system/bin/zlink5.sh") })
    }

    @Test
    fun noCodosCommandAsksForSu() {
        for (target in OemAppManager.KNOWN_TARGETS) {
            val all = OemAppManager.getDisableCommands(null, status(target), codos = true) +
                OemAppManager.getRestoreCommands(null, status(target), codos = true)
            assertFalse(all.joinToString(" ; "), all.any { Regex("\\bsu\\b").containsMatchIn(it) })
        }
    }

    @Test
    fun onlyTheSystemUidCountsAsSystem() {
        assertTrue(SystemUidShell.isSystemUid(1000))
        assertFalse(SystemUidShell.isSystemUid(0))
        assertFalse(SystemUidShell.isSystemUid(2000))
        assertFalse(SystemUidShell.isSystemUid(10123))
    }
}
