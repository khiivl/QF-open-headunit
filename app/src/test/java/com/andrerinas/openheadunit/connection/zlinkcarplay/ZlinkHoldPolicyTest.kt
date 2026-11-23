package com.andrerinas.openheadunit.connection.zlinkcarplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ZlinkHoldPolicyTest {
    private fun buildFrame(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        out[0] = 0xFF.toByte(); out[1] = 0xFF.toByte(); out[2] = 0xFF.toByte(); out[3] = 0x10
        val len = payload.size
        out[4] = (len ushr 24).toByte(); out[5] = (len ushr 16).toByte()
        out[6] = (len ushr 8).toByte(); out[7] = len.toByte()
        out[8] = (type ushr 24).toByte(); out[9] = (type ushr 16).toByte()
        out[10] = (type ushr 8).toByte(); out[11] = type.toByte()
        System.arraycopy(payload, 0, out, 12, len)
        return out
    }

    private fun build0x102(mode: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(0x08); baos.write(1)
        baos.write(0x20); baos.write(30)   // field 4, the frame rate the app asks for
        baos.write(0x38); baos.write(mode) // field 7
        return buildFrame(0x102, baos.toByteArray())
    }

    @Test fun holdPolicyTriggersOnFirstAndLastSubscriber() {
        val policy = ZlinkHoldPolicy()

        // App sends real 0x102 with mode 0 before any subscriber attaches
        val appFrame = build0x102(0)
        val forwarded = policy.onAppInitFrame(appFrame, subscriberCount = 0)
        assertEquals(0L, ZlinkMessage.fieldValue(forwarded, 7))

        // First subscriber attaches (0 -> 1): must send mode 2 to daemon
        val toDaemonOnFirst = policy.onSubscriberCountChanged(0, 1)
        assertNotNull(toDaemonOnFirst)
        assertEquals(2L, ZlinkMessage.fieldValue(toDaemonOnFirst!!, 7))

        // Second subscriber attaches (1 -> 2): no change needed
        val toDaemonOnSecond = policy.onSubscriberCountChanged(1, 2)
        assertNull(toDaemonOnSecond)

        // One subscriber leaves (2 -> 1): no change needed
        val toDaemonOnDropToOne = policy.onSubscriberCountChanged(2, 1)
        assertNull(toDaemonOnDropToOne)

        // Last subscriber leaves (1 -> 0): must NOT send mode 0 so backgrounding / going Home keeps CarPlay alive
        val toDaemonOnLast = policy.onSubscriberCountChanged(1, 0)
        assertNull(toDaemonOnLast)
    }

    @Test fun appInitFrameRewrittenWhileSubscribersAttached() {
        val policy = ZlinkHoldPolicy()

        // App sends mode 0 while 1 subscriber is attached
        val appFrame = build0x102(0)
        val forwarded = policy.onAppInitFrame(appFrame, subscriberCount = 1)
        // Must be rewritten to 2 for forwarding to the daemon
        assertEquals(2L, ZlinkMessage.fieldValue(forwarded, 7))
        // But the stored lastInitFrame must remain the app's real frame (mode 0)
        assertEquals(0L, ZlinkMessage.fieldValue(policy.lastInitFrame!!, 7))
    }

    @Test fun audioSuppressionRule() {
        val policy = ZlinkHoldPolicy()

        // Daemon-to-app audio (port 1666, d2a) suppressed if subscribers > 0
        assertFalse(policy.shouldForwardAudioToApp(port = 1666, d2a = true, subscriberCount = 1))
        assertFalse(policy.shouldForwardAudioToApp(port = 1666, d2a = true, subscriberCount = 2))

        // Allowed if no subscribers
        assertTrue(policy.shouldForwardAudioToApp(port = 1666, d2a = true, subscriberCount = 0))

        // App-to-daemon audio allowed
        assertTrue(policy.shouldForwardAudioToApp(port = 1666, d2a = false, subscriberCount = 1))

        // Other ports (control 1777, video 1888) allowed
        assertTrue(policy.shouldForwardAudioToApp(port = 1777, d2a = true, subscriberCount = 1))
        assertTrue(policy.shouldForwardAudioToApp(port = 1888, d2a = true, subscriberCount = 1))
    }

    @Test fun suspendAndResumeOffer() {
        val policy = ZlinkHoldPolicy()
        policy.onAppInitFrame(build0x102(0), subscriberCount = 1)

        // Suspend offer (e.g. user disconnect or power off): sends mode 0
        val suspendFrame = policy.suspendOffer()
        assertEquals(0L, ZlinkMessage.fieldValue(suspendFrame, 7))
        assertTrue(policy.offerSuspended)

        // Incoming app frames while suspended are not rewritten
        val forwardedWhileSuspended = policy.onAppInitFrame(build0x102(0), subscriberCount = 1)
        assertEquals(0L, ZlinkMessage.fieldValue(forwardedWhileSuspended, 7))

        // Resume offer: sends mode 2
        val resumeFrame = policy.resumeOffer(subscriberCount = 0)
        assertNotNull(resumeFrame)
        assertEquals(2L, ZlinkMessage.fieldValue(resumeFrame!!, 7))
        assertFalse(policy.offerSuspended)
    }

    @Test fun suspendedOfferKeepsTheAppsOwnCarPlayOfferOff() {
        val policy = ZlinkHoldPolicy()
        policy.onAppInitFrame(build0x102(0), subscriberCount = 1)
        policy.suspendOffer()

        // The daemon restarts after a Disconnect and the app offers wireless CarPlay itself
        val forwarded = policy.onAppInitFrame(build0x102(2), subscriberCount = 0)
        assertEquals(0L, ZlinkMessage.fieldValue(forwarded, 7))
    }

    @Test fun initAsksTheDaemonForTheTargetFrameRate() {
        val policy = ZlinkHoldPolicy()
        val forwarded = policy.onAppInitFrame(build0x102(0), subscriberCount = 0)
        assertEquals(ZlinkHoldPolicy.TARGET_FPS.toLong(), ZlinkMessage.fieldValue(forwarded, ZlinkMessage.FIELD_FPS))
        // and the frames made later from it carry the same rate
        val suspended = policy.suspendOffer()
        assertEquals(ZlinkHoldPolicy.TARGET_FPS.toLong(), ZlinkMessage.fieldValue(suspended, ZlinkMessage.FIELD_FPS))
    }
}
