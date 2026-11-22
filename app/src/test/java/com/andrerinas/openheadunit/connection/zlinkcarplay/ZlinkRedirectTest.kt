package com.andrerinas.openheadunit.connection.zlinkcarplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlinkRedirectTest {
    @Test fun ruleCheckFormat() {
        val check = ZlinkRedirect.ruleCheck(1666)
        assertEquals(
            "iptables -t nat -C OUTPUT -p tcp -d 127.0.0.1 --dport 1666 -m owner --uid-owner 0 -j REDIRECT --to-ports 11666",
            check
        )
    }

    @Test fun ruleAddFormat() {
        val add = ZlinkRedirect.ruleAdd(1777)
        assertEquals(
            "iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport 1777 -m owner --uid-owner 0 -j REDIRECT --to-ports 11777",
            add
        )
    }

    @Test fun ruleDeleteFormat() {
        val del = ZlinkRedirect.ruleDelete(1888)
        assertEquals(
            "iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport 1888 -m owner --uid-owner 0 -j REDIRECT --to-ports 11888",
            del
        )
    }

    @Test fun buildEnsureCommandIsIdempotentForAllPorts() {
        val ensureCmd = ZlinkRedirect.buildEnsureCommand(listOf(1666, 1777, 1888, 1999))
        for (p in listOf(1666, 1777, 1888, 1999)) {
            val target = p + 10000
            assertTrue(ensureCmd.contains("iptables -t nat -C OUTPUT -p tcp -d 127.0.0.1 --dport $p -m owner --uid-owner 0 -j REDIRECT --to-ports $target 2>/dev/null || iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport $p -m owner --uid-owner 0 -j REDIRECT --to-ports $target"))
        }
    }

    @Test fun buildRemoveCommandDeletesIfPresent() {
        val removeCmd = ZlinkRedirect.buildRemoveCommand(listOf(1666, 1777, 1888, 1999))
        for (p in listOf(1666, 1777, 1888, 1999)) {
            val target = p + 10000
            assertTrue(removeCmd.contains("iptables -t nat -C OUTPUT -p tcp -d 127.0.0.1 --dport $p -m owner --uid-owner 0 -j REDIRECT --to-ports $target 2>/dev/null && iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport $p -m owner --uid-owner 0 -j REDIRECT --to-ports $target || true"))
        }
    }

    @Test fun ensureIsSkippedRightAfterASuccessButNotBefore() {
        assertTrue(ZlinkRedirect.shouldEnsure(nowMs = 1_000L, lastOkMs = 0L))
        assertFalse(ZlinkRedirect.shouldEnsure(nowMs = 11_000L, lastOkMs = 1_000L))
        assertTrue(ZlinkRedirect.shouldEnsure(nowMs = 31_001L, lastOkMs = 1_000L))
    }
}
