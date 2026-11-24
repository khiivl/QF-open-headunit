package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionSessionTest {

    private val connected = ProjectionSessionPolicy.Message("CONNECTED", "auto_wireless")

    @Test fun upOnlyAfterTheHandshake() {
        assertTrue(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.HandshakeComplete))
        assertTrue(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.TransportStarted))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Connecting))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Connected))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.StartingTransport))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Error("x")))
        assertFalse(ProjectionSessionPolicy.isUp(CommManager.ConnectionState.Disconnected()))
    }

    @Test fun theBroadcastIsZLinksOwnWords() {
        assertEquals("com.zjinnova.zlink", ProjectionSessionPolicy.ACTION)
        assertEquals("status", ProjectionSessionPolicy.EXTRA_STATUS)
        assertEquals("phoneMode", ProjectionSessionPolicy.EXTRA_PHONE_MODE)
        assertEquals(ProjectionSessionPolicy.Message("CONNECTED", "auto_wired"), ProjectionSessionPolicy.message(true, "auto_wired"))
        assertEquals(ProjectionSessionPolicy.Message("DISCONNECT", "auto_wireless"), ProjectionSessionPolicy.message(false, "auto_wireless"))
    }

    @Test fun aCableIsWiredAnythingElseIsWireless() {
        assertEquals("auto_wired", ProjectionSessionPolicy.phoneMode(ProjectionConnection.Type.USB))
        assertEquals("auto_wireless", ProjectionSessionPolicy.phoneMode(ProjectionConnection.Type.WIFI))
        assertEquals("auto_wireless", ProjectionSessionPolicy.phoneMode(null))
    }

    @Test fun writerSendsOnlyOnChange() {
        val sent = mutableListOf<ProjectionSessionPolicy.Message>()
        val w = ProjectionSessionWriter({ sent += it })
        w.apply(true, ProjectionConnection.Type.WIFI)
        w.apply(true, ProjectionConnection.Type.WIFI)
        w.apply(false)
        w.apply(false)
        assertEquals(listOf(connected, ProjectionSessionPolicy.Message("DISCONNECT", "auto_wireless")), sent)
    }

    @Test fun theDisconnectCarriesTheModeOfTheConnectItEnds() {
        val sent = mutableListOf<ProjectionSessionPolicy.Message>()
        val w = ProjectionSessionWriter({ sent += it })
        w.apply(true, ProjectionConnection.Type.USB)
        w.apply(false)
        assertEquals(
            listOf(ProjectionSessionPolicy.Message("CONNECTED", "auto_wired"), ProjectionSessionPolicy.Message("DISCONNECT", "auto_wired")),
            sent
        )
    }

    @Test fun aFailedSendIsRetriedOnTheNextChange() {
        var fail = true
        val sent = mutableListOf<ProjectionSessionPolicy.Message>()
        var failures = 0
        val w = ProjectionSessionWriter({ if (fail) throw IllegalStateException("denied") else sent += it }, { failures++ })
        w.apply(true, ProjectionConnection.Type.WIFI)
        assertEquals(1, failures)
        assertTrue(sent.isEmpty())
        fail = false
        w.apply(true, ProjectionConnection.Type.WIFI)
        assertEquals(listOf(connected), sent)
    }

    @Test fun startingFreshSendsADisconnectOnlyIfTheSystemThinksASessionIsUp() {
        val sent = mutableListOf<ProjectionSessionPolicy.Message>()
        val w = ProjectionSessionWriter({ sent += it })
        w.startFresh(false)
        assertTrue(sent.isEmpty())
        w.startFresh(true)
        assertEquals(listOf(ProjectionSessionPolicy.Message("DISCONNECT", "auto_wireless")), sent)
        w.apply(false) // already down for this process: nothing more
        assertEquals(1, sent.size)
        w.apply(true, ProjectionConnection.Type.WIFI)
        assertEquals(connected, sent.last())
    }
}
