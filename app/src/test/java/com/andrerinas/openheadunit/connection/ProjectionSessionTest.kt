package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionSessionTest {


    @Test fun upOnlyAfterTheHandshake() {
        assertTrue(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.HandshakeComplete))
        assertTrue(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.TransportStarted))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Connecting))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Connected))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.StartingTransport))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Error("x")))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Disconnected()))
    }

    @Test fun pidIsWrittenBeforeTheFlag() {
        val w = ProjectionSessionPolicy.writes(true, 4321)
        assertEquals(listOf("sys.qf.ohu.pid" to "4321", "sys.qf.ohu.connect" to "true"), w)
    }

    @Test fun downWritesOnlyTheFlag() {
        assertEquals(listOf("sys.qf.ohu.connect" to "false"), ProjectionSessionPolicy.writes(false, 4321))
    }

    @Test fun startDropsWhateverAnEarlierProcessLeft() {
        assertEquals(
            listOf("sys.qf.ohu.connect" to "false", "sys.qf.ohu.pid" to ""),
            ProjectionSessionPolicy.startWrites()
        )
    }

    @Test fun writerWritesOnlyOnChange() {
        val log = mutableListOf<Pair<String, String>>()
        val w = ProjectionSessionWriter({ 7 }, { k, v -> log += k to v })
        w.apply(true)
        w.apply(true)
        w.apply(false)
        w.apply(false)
        assertEquals(
            listOf("sys.qf.ohu.pid" to "7", "sys.qf.ohu.connect" to "true", "sys.qf.ohu.connect" to "false"),
            log
        )
    }

    @Test fun aFailedWriteIsRetriedOnTheNextChange() {
        var fail = true
        val log = mutableListOf<Pair<String, String>>()
        var failures = 0
        val w = ProjectionSessionWriter({ 7 }, { k, v -> if (fail) throw IllegalStateException("denied") else log += k to v }, { failures++ })
        w.apply(true)
        assertEquals(1, failures)
        assertTrue(log.isEmpty())
        fail = false
        w.apply(true)
        assertEquals(listOf("sys.qf.ohu.pid" to "7", "sys.qf.ohu.connect" to "true"), log)
    }

    @Test fun clearAtStartThenFirstUpWritesAgain() {
        val log = mutableListOf<Pair<String, String>>()
        val w = ProjectionSessionWriter({ 9 }, { k, v -> log += k to v })
        w.clearAtStart()
        w.apply(false) // already published as down: nothing more
        assertEquals(2, log.size)
        w.apply(true)
        assertEquals("sys.qf.ohu.connect" to "true", log.last())
    }
}
