package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.Intent
import com.andrerinas.openheadunit.aap.AapService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZlinkPowerPolicyTest {

    private var fakeTime = 1_000L
    private val offEvents = mutableListOf<String>()
    private val onEvents = mutableListOf<String>()

    private lateinit var policy: ZlinkPowerPolicy

    @Before
    fun setUp() {
        fakeTime = 1_000L
        offEvents.clear()
        onEvents.clear()
        policy = ZlinkPowerPolicy(
            clock = { fakeTime },
            listener = object : ZlinkPowerPolicy.Listener {
                override fun onPowerOff(action: String) {
                    offEvents.add(action)
                }

                override fun onPowerOn(action: String) {
                    onEvents.add(action)
                }
            }
        )
    }

    @Test
    fun `every ACC_OFF action triggers power off immediately`() {
        for (action in AapService.ACC_OFF_ACTIONS) {
            offEvents.clear()
            policy.onAction(action)
            assertEquals("Expected immediate off for $action", listOf(action), offEvents)
            assertTrue("Expected no on events for $action", onEvents.isEmpty())
            assertFalse(policy.hasPendingScreenOff)
        }
    }

    @Test
    fun `ACTION_SHUTDOWN triggers power off immediately`() {
        policy.onAction(Intent.ACTION_SHUTDOWN)
        assertEquals(listOf(Intent.ACTION_SHUTDOWN), offEvents)
        assertTrue(onEvents.isEmpty())
        assertFalse(policy.hasPendingScreenOff)
    }

    @Test
    fun `every ACC_ON action triggers power on immediately`() {
        for (action in AapService.ACC_ON_ACTIONS) {
            onEvents.clear()
            policy.onAction(action)
            assertEquals("Expected immediate on for $action", listOf(action), onEvents)
            assertTrue("Expected no off events for $action", offEvents.isEmpty())
            assertFalse(policy.hasPendingScreenOff)
        }
    }

    @Test
    fun `ACTION_SCREEN_ON triggers power on immediately`() {
        policy.onAction(Intent.ACTION_SCREEN_ON)
        assertEquals(listOf(Intent.ACTION_SCREEN_ON), onEvents)
        assertTrue(offEvents.isEmpty())
        assertFalse(policy.hasPendingScreenOff)
    }

    @Test
    fun `ACTION_BOOT_COMPLETED triggers power on immediately`() {
        policy.onAction(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(listOf(Intent.ACTION_BOOT_COMPLETED), onEvents)
        assertTrue(offEvents.isEmpty())
        assertFalse(policy.hasPendingScreenOff)
    }

    @Test
    fun `ACTION_POWER_DISCONNECTED alone does nothing`() {
        policy.onAction(Intent.ACTION_POWER_DISCONNECTED)
        assertTrue(offEvents.isEmpty())
        assertTrue(onEvents.isEmpty())
        assertFalse(policy.hasPendingScreenOff)
    }

    @Test
    fun `unknown intent does nothing`() {
        policy.onAction("some.random.unrelated.ACTION")
        assertTrue(offEvents.isEmpty())
        assertTrue(onEvents.isEmpty())
        assertFalse(policy.hasPendingScreenOff)
    }

    @Test
    fun `SCREEN_OFF ends session only after 3 seconds`() {
        policy.onAction(Intent.ACTION_SCREEN_OFF, now = 1_000L)
        assertTrue("No off event immediately on SCREEN_OFF", offEvents.isEmpty())
        assertTrue(policy.hasPendingScreenOff)

        // Tick at 2.9 seconds -> still no off
        policy.tick(now = 3_999L)
        assertTrue("Still no off before 3s window", offEvents.isEmpty())
        assertTrue(policy.hasPendingScreenOff)

        // Tick at exactly 3.0 seconds -> off fires
        policy.tick(now = 4_000L)
        assertEquals(listOf(Intent.ACTION_SCREEN_OFF), offEvents)
        assertFalse(policy.hasPendingScreenOff)

        // Subsequent ticks do not re-trigger
        policy.tick(now = 5_000L)
        assertEquals(listOf(Intent.ACTION_SCREEN_OFF), offEvents)
    }

    @Test
    fun `SCREEN_OFF followed by ACC_ON before 3s cancels pending screen off`() {
        policy.onAction(Intent.ACTION_SCREEN_OFF, now = 1_000L)
        assertTrue(policy.hasPendingScreenOff)

        // ACC_ON arrives at 2.0s
        policy.onAction("com.qf.action.ACC_ON", now = 3_000L)
        assertEquals(listOf("com.qf.action.ACC_ON"), onEvents)
        assertFalse("Pending screen off should be cancelled", policy.hasPendingScreenOff)

        // Tick at 5.0s -> screen off must NOT fire
        policy.tick(now = 6_000L)
        assertTrue("Screen off must not fire after ACC_ON", offEvents.isEmpty())
    }

    @Test
    fun `SCREEN_OFF followed by SCREEN_ON before 3s cancels pending screen off`() {
        policy.onAction(Intent.ACTION_SCREEN_OFF, now = 1_000L)
        assertTrue(policy.hasPendingScreenOff)

        policy.onAction(Intent.ACTION_SCREEN_ON, now = 2_500L)
        assertEquals(listOf(Intent.ACTION_SCREEN_ON), onEvents)
        assertFalse("Pending screen off should be cancelled", policy.hasPendingScreenOff)

        policy.tick(now = 5_000L)
        assertTrue("Screen off must not fire after SCREEN_ON", offEvents.isEmpty())
    }

    @Test
    fun `SCREEN_OFF followed by ACC_OFF before 3s triggers immediate off and clears pending`() {
        policy.onAction(Intent.ACTION_SCREEN_OFF, now = 1_000L)
        assertTrue(policy.hasPendingScreenOff)

        policy.onAction("com.qf.action.ACC_OFF", now = 2_000L)
        assertEquals(listOf("com.qf.action.ACC_OFF"), offEvents)
        assertFalse("Pending screen off should be cleared", policy.hasPendingScreenOff)

        // Tick at 5.0s -> no duplicate SCREEN_OFF event
        policy.tick(now = 5_000L)
        assertEquals(listOf("com.qf.action.ACC_OFF"), offEvents)
    }
}
