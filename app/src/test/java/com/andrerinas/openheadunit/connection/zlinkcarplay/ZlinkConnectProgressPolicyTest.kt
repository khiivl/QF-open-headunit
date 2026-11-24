package com.andrerinas.openheadunit.connection.zlinkcarplay

import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkConnectProgressPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class ZlinkConnectProgressPolicyTest {

    private fun step(
        sessionActive: Boolean = false,
        offerSuspended: Boolean = false,
        elapsedMs: Long = 1_000,
        phoneForMs: Long? = null
    ) = ZlinkConnectProgressPolicy.step(sessionActive, offerSuspended, elapsedMs, phoneForMs)

    @Test fun `the offer is out and no phone has shown up`() = assertEquals(Step.WAKING, step())

    @Test fun `a phone that has only just appeared is not yet a connection`() =
        assertEquals(Step.WAKING, step(phoneForMs = ZlinkConnectProgressPolicy.CONNECTING_AFTER_PHONE_MS - 1))

    @Test fun `a phone that stays on the module is a connection in progress`() =
        assertEquals(Step.CONNECTING, step(phoneForMs = ZlinkConnectProgressPolicy.CONNECTING_AFTER_PHONE_MS))

    @Test fun `a session that is up ends it`() =
        assertEquals(Step.STOP, step(sessionActive = true, phoneForMs = 10_000))

    @Test fun `a held-off offer ends it, the user's Cancel or a power off`() =
        assertEquals(Step.STOP, step(offerSuspended = true))

    @Test fun `the wait runs out`() {
        assertEquals(Step.WAKING, step(elapsedMs = ZlinkConnectProgressPolicy.DEADLINE_MS - 1))
        assertEquals(Step.STOP, step(elapsedMs = ZlinkConnectProgressPolicy.DEADLINE_MS))
    }
}
