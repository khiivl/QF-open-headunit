package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateLogTest {

    private fun captured(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val original = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { lines.add(msg.substringAfter(" | ")) }
        }
        try { block() } finally { AppLog.LOGGER = original }
        return lines
    }

    @Test
    fun `a state change says from, to and the mode`() {
        val lines = captured {
            ConnectionStateLog.state(CommManager.ConnectionState.Connecting, CommManager.ConnectionState.Connected, "WIFI")
        }
        assertEquals(listOf("ConnState: Connecting -> Connected [mode=WIFI]"), lines)
    }

    @Test
    fun `an error and a disconnect carry what ended them`() {
        assertEquals("Error(boom)", ConnectionStateLog.describe(CommManager.ConnectionState.Error("boom")))
        val d = ConnectionStateLog.describe(CommManager.ConnectionState.Disconnected(isClean = true, reachedSsl = true))
        assertTrue(d, d.startsWith("Disconnected(") && d.contains("clean=true") && d.contains("reachedSsl=true"))
    }

    @Test
    fun `a stage that did not change is not logged`() {
        val lines = captured {
            ConnectionStateLog.stage(ConnectionStage.SEARCHING, ConnectionStage.SEARCHING, "report")
            ConnectionStateLog.stage(ConnectionStage.SEARCHING, ConnectionStage.WAKING_PHONE, "report")
            ConnectionStateLog.stage(ConnectionStage.WAKING_PHONE, null, "endAttempt")
        }
        assertEquals(listOf("ConnStage: SEARCHING -> WAKING_PHONE [report]", "ConnStage: WAKING_PHONE -> none [endAttempt]"), lines)
    }
}
