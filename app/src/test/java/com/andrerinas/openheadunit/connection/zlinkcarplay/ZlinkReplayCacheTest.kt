package com.andrerinas.openheadunit.connection.zlinkcarplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlinkReplayCacheTest {
    private fun makeVideoFrame(nalType: Int, size: Int = 100): ZlinkWire.Frame {
        val payload = ByteArray(24 + size)
        // 24-byte media header
        payload[0] = 0; payload[1] = 0; payload[2] = 4; payload[3] = 0 // width 1024
        payload[4] = 0; payload[5] = 0; payload[6] = 2; payload[7] = 0x60.toByte() // height 608
        // byte 24: nalType
        payload[24] = (nalType and 0x1F).toByte()
        return ZlinkWire.Frame(ZlinkWire.CH_VIDEO, ZlinkWire.TYPE_VIDEO, payload)
    }

    @Test fun spsHandlingDoesNotValidateCacheWithoutIdr() {
        val cache = ZlinkReplayCache()
        assertFalse(cache.isValid)
        assertTrue(cache.getReplayFrames().isEmpty())

        // Feed SPS (nal 7)
        val sps = makeVideoFrame(7, 50)
        cache.onVideoFrame(sps)
        assertFalse(cache.isValid)
        assertTrue(cache.getReplayFrames().isEmpty())

        // Feed PPS (nal 8)
        val pps = makeVideoFrame(8, 20)
        cache.onVideoFrame(pps)
        assertFalse(cache.isValid)
        assertTrue(cache.getReplayFrames().isEmpty())
    }

    @Test fun idrValidatesCacheAndIncludesSpsAndPps() {
        val cache = ZlinkReplayCache()
        val sps = makeVideoFrame(7, 50)
        val pps = makeVideoFrame(8, 20)
        cache.onVideoFrame(sps)
        cache.onVideoFrame(pps)

        // Feed IDR (nal 5)
        val idr1 = makeVideoFrame(5, 500)
        cache.onVideoFrame(idr1)
        assertTrue(cache.isValid)
        assertEquals(3, cache.getReplayFrames().size)
        assertEquals(7, cache.getReplayFrames()[0].payload[24].toInt() and 0x1F)
        assertEquals(8, cache.getReplayFrames()[1].payload[24].toInt() and 0x1F)
        assertEquals(5, cache.getReplayFrames()[2].payload[24].toInt() and 0x1F)

        // Feed P-frame (nal 1)
        val p1 = makeVideoFrame(1, 200)
        cache.onVideoFrame(p1)
        assertEquals(4, cache.getReplayFrames().size)

        // Feed second IDR (nal 5): should clear old GOP, re-include SPS/PPS, and add new IDR
        val idr2 = makeVideoFrame(5, 600)
        cache.onVideoFrame(idr2)
        assertTrue(cache.isValid)
        val frames = cache.getReplayFrames()
        assertEquals(3, frames.size)
        assertEquals(sps, frames[0])
        assertEquals(pps, frames[1])
        assertEquals(idr2, frames[2])
    }

    @Test fun gopCapInvalidatesCacheUntilNextIdr() {
        // Use a small cap for testing (e.g. 1000 bytes)
        val cache = ZlinkReplayCache(maxGopBytes = 1000)
        val sps = makeVideoFrame(7, 40)
        cache.onVideoFrame(sps)

        val idr = makeVideoFrame(5, 200)
        cache.onVideoFrame(idr)
        assertTrue(cache.isValid)

        // Add P-frames until cap exceeded
        val p1 = makeVideoFrame(1, 400)
        cache.onVideoFrame(p1)
        assertTrue(cache.isValid)

        val p2 = makeVideoFrame(1, 500) // will push total over 1000 bytes
        cache.onVideoFrame(p2)
        assertFalse("GOP must be invalidated when exceeding cap", cache.isValid)
        assertTrue(cache.getReplayFrames().isEmpty())

        // Non-IDR frames arriving while invalid are not added
        val p3 = makeVideoFrame(1, 100)
        cache.onVideoFrame(p3)
        assertFalse(cache.isValid)
        assertTrue(cache.getReplayFrames().isEmpty())

        // New IDR recovers the cache
        val idr2 = makeVideoFrame(5, 200)
        cache.onVideoFrame(idr2)
        assertTrue(cache.isValid)
        assertEquals(2, cache.getReplayFrames().size) // SPS + IDR2
    }
}
