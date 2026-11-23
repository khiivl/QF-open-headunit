package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotConfigClientTest {

    @Test fun acceptsAnOrdinaryNameAndPassword() {
        assertNull(HotspotConfigClient.problem("My Hotspot", "pass-word_123"))
    }

    @Test fun refusesLengthsTheHotspotWouldRefuse() {
        assertNotNull(HotspotConfigClient.problem("", "password1"))
        assertNotNull(HotspotConfigClient.problem("x".repeat(33), "password1"))
        assertNotNull(HotspotConfigClient.problem("name", "short"))
        assertNotNull(HotspotConfigClient.problem("name", "x".repeat(64)))
    }

    @Test fun refusesCharactersThatCouldBreakOutOfTheCommand() {
        for (bad in listOf("a'b", "a\"b", "a\$b", "a`b", "a\b", "a;rm -rf /'")) {
            assertNotNull("should refuse: $bad", HotspotConfigClient.problem("name", bad.padEnd(8, 'x')))
        }
    }

    @Test fun theCommandQuotesTheValues() {
        val cmd = HotspotConfigClient.buildSetCommand("pkg.name", "My Name", "pass1234", codos = false)
        assertTrue(cmd.contains("set 'My Name' 'pass1234'"))
        assertTrue(cmd.contains("su 1000"))
        assertEquals(1, Regex("su 1000").findAll(cmd).count())
    }

    @Test fun onCodosTheCommandRunsDirectlyWithoutSu() {
        val set = HotspotConfigClient.buildSetCommand("pkg.name", "My Name", "pass1234", codos = true)
        val ensure = HotspotConfigClient.buildEnsure5GhzCommand("pkg.name", codos = true)
        assertTrue(set.contains("set 'My Name' 'pass1234'"))
        assertTrue(ensure.contains("HotspotConfigTool ensure-5ghz"))
        assertTrue(!set.contains("su ") && !ensure.contains("su "))
    }
}
