package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionSessionTest {

    private val flag = "sys.qf.bt.aa.connect"

    @Test fun upOnlyAfterTheHandshake() {
        assertTrue(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.HandshakeComplete))
        assertTrue(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.TransportStarted))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Connecting))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Connected))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.StartingTransport))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Error("x")))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Disconnected()))
    }

    @Test fun theOneVariableIsTheExistingAndroidAutoFlag() {
        assertEquals("sys.qf.bt.aa.connect", ProjectionSessionPolicy.PROP_AA_CONNECT)
        assertEquals(listOf(flag to "true"), ProjectionSessionPolicy.writes(true))
        assertEquals(listOf(flag to "false"), ProjectionSessionPolicy.writes(false))
    }

    @Test fun startDropsWhateverAnEarlierProcessLeft() {
        assertEquals(listOf(flag to "false"), ProjectionSessionPolicy.startWrites())
    }

    @Test fun writerWritesOnlyOnChange() {
        val log = mutableListOf<Pair<String, String>>()
        val w = ProjectionSessionWriter({ k, v -> log += k to v })
        w.apply(true)
        w.apply(true)
        w.apply(false)
        w.apply(false)
        assertEquals(listOf(flag to "true", flag to "false"), log)
    }

    @Test fun aFailedWriteIsRetriedOnTheNextChange() {
        var fail = true
        val log = mutableListOf<Pair<String, String>>()
        var failures = 0
        val w = ProjectionSessionWriter({ k, v -> if (fail) throw IllegalStateException("denied") else log += k to v }, { failures++ })
        w.apply(true)
        assertEquals(1, failures)
        assertTrue(log.isEmpty())
        fail = false
        w.apply(true)
        assertEquals(listOf(flag to "true"), log)
    }

    @Test fun clearAtStartThenFirstUpWritesAgain() {
        val log = mutableListOf<Pair<String, String>>()
        val w = ProjectionSessionWriter({ k, v -> log += k to v })
        w.clearAtStart()
        w.apply(false) // already published as down: nothing more
        assertEquals(1, log.size)
        w.apply(true)
        assertEquals(flag to "true", log.last())
    }
}
