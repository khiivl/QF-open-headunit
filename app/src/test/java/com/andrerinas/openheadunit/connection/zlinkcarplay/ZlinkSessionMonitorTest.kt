package com.andrerinas.openheadunit.connection.zlinkcarplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ZlinkSessionMonitorTest {
    private class FakeClock(var time: Long = 1000L) {
        fun advance(ms: Long) { time += ms }
    }

    @Test fun initialConnectedAndMaintainedByHeartbeat() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)

        // Heartbeats every 2 seconds
        repeat(10) {
            clock.advance(2000L)
            monitor.onHeartbeat()
            monitor.tick()
            assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
        }
        assertEquals(0, states.size)
    }

    @Test fun heartbeatsStopTransitionsToLostAt7Seconds() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        clock.advance(6_999L)
        monitor.tick()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)

        clock.advance(1L) // exactly 7000ms
        monitor.tick()
        assertEquals(ZlinkSessionState.LOST, monitor.state)
        assertEquals(listOf(ZlinkSessionState.LOST), states)
    }

    @Test fun daemonSocketClosedTransitionsImmediatelyToLost() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        clock.advance(1000L)
        monitor.onDaemonSocketClosed()
        assertEquals(ZlinkSessionState.LOST, monitor.state)
        assertEquals(listOf(ZlinkSessionState.LOST), states)

        // Duplicate socket closed calls do not re-trigger
        monitor.onDaemonSocketClosed()
        assertEquals(1, states.size)
    }

    @Test fun recoveryFromLostByHeartbeat() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        clock.advance(7000L)
        monitor.tick()
        assertEquals(ZlinkSessionState.LOST, monitor.state)

        clock.advance(1000L)
        monitor.onHeartbeat()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
        assertEquals(listOf(ZlinkSessionState.LOST, ZlinkSessionState.CONNECTED), states)
    }

    @Test fun recoveryFromLostBySps() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        monitor.onDaemonSocketClosed()
        assertEquals(ZlinkSessionState.LOST, monitor.state)

        clock.advance(5000L)
        monitor.onSps()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
        assertEquals(listOf(ZlinkSessionState.LOST, ZlinkSessionState.CONNECTED), states)
    }

    @Test fun lostFor15SecondsTransitionsToGaveUp() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        monitor.onDaemonSocketClosed() // enters LOST at 10_000
        assertEquals(ZlinkSessionState.LOST, monitor.state)

        clock.advance(14_999L)
        monitor.tick()
        assertEquals(ZlinkSessionState.LOST, monitor.state)

        clock.advance(1L) // exactly 15_000ms in LOST
        monitor.tick()
        assertEquals(ZlinkSessionState.GAVE_UP, monitor.state)
        assertEquals(listOf(ZlinkSessionState.LOST, ZlinkSessionState.GAVE_UP), states)

        // Further ticks stay in GAVE_UP
        clock.advance(10_000L)
        monitor.tick()
        assertEquals(ZlinkSessionState.GAVE_UP, monitor.state)
        assertEquals(2, states.size)
    }

    @Test fun spsRecoversFromGaveUp() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        monitor.onDaemonSocketClosed()
        clock.advance(15_000L)
        monitor.tick()
        assertEquals(ZlinkSessionState.GAVE_UP, monitor.state)

        // New session begins with SPS
        clock.advance(5_000L)
        monitor.onSps()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
        assertEquals(
            listOf(ZlinkSessionState.LOST, ZlinkSessionState.GAVE_UP, ZlinkSessionState.CONNECTED),
            states
        )
    }

    @Test fun heartbeatRecoversFromGaveUp() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        monitor.markGaveUp()
        assertEquals(ZlinkSessionState.GAVE_UP, monitor.state)

        clock.advance(2_000L)
        monitor.onHeartbeat()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
        assertEquals(listOf(ZlinkSessionState.GAVE_UP, ZlinkSessionState.CONNECTED), states)
    }

    @Test fun mediaFrameRecoversFromLostAndGaveUp() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        monitor.onDaemonSocketClosed()
        assertEquals(ZlinkSessionState.LOST, monitor.state)

        clock.advance(1_000L)
        monitor.onMediaFrame()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)

        clock.advance(7_000L)
        monitor.tick()
        clock.advance(15_000L)
        monitor.tick()
        assertEquals(ZlinkSessionState.GAVE_UP, monitor.state)

        clock.advance(1_000L)
        monitor.onMediaFrame()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
    }

    @Test fun resetRestoresConnected() {
        val clock = FakeClock(10_000L)
        val states = mutableListOf<ZlinkSessionState>()
        val monitor = ZlinkSessionMonitor(clock = { clock.time }, onStateChanged = { states.add(it) })

        monitor.markGaveUp()
        assertEquals(ZlinkSessionState.GAVE_UP, monitor.state)

        monitor.reset()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
        assertEquals(listOf(ZlinkSessionState.GAVE_UP, ZlinkSessionState.CONNECTED), states)
    }

    @Test fun mediaFrameRefreshesHeartbeatTimer() {
        val clock = FakeClock(10_000L)
        val monitor = ZlinkSessionMonitor(clock = { clock.time })

        clock.advance(5000L)
        monitor.onMediaFrame()
        clock.advance(5000L) // 10s from start, but only 5s since media frame
        monitor.tick()
        assertEquals(ZlinkSessionState.CONNECTED, monitor.state)
    }
}
