package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinRefusalRestartPolicyTest {

    @Test fun `one refusal is not enough`() = assertFalse(JoinRefusalRestartPolicy.shouldRestart(1, 0, true))

    @Test fun `two refusals on a zLink unit restart the bring-up`() = assertTrue(JoinRefusalRestartPolicy.shouldRestart(2, 0, true))

    @Test fun `other units keep the slow retries`() = assertFalse(JoinRefusalRestartPolicy.shouldRestart(5, 0, false))

    @Test fun `restarts are capped so a phone that always refuses is not hammered`() {
        assertTrue(JoinRefusalRestartPolicy.shouldRestart(2, JoinRefusalRestartPolicy.MAX_RESTARTS - 1, true))
        assertFalse(JoinRefusalRestartPolicy.shouldRestart(2, JoinRefusalRestartPolicy.MAX_RESTARTS, true))
    }

    @Test fun `a landed session clears the count`() {
        JoinRefusalRestartPolicy.noteRestart()
        JoinRefusalRestartPolicy.noteLanded()
        assertTrue(JoinRefusalRestartPolicy.restartsSinceLanding == 0)
    }
}
