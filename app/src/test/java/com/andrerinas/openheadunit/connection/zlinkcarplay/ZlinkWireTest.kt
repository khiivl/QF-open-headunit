package com.andrerinas.openheadunit.connection.zlinkcarplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

class ZlinkWireTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun touchEncodingMatchesDaemonCapture() {
        // Captured from the real zLink app: x=740 y=268 down.
        assertArrayEquals(hex("ffffff100000000c000001110a0a080010e405188c022001"), ZlinkWire.encodeTouch(740, 268, true))
        // Captured release event: x=304 y=316 up.
        assertArrayEquals(hex("ffffff100000000c000001110a0a080010b00218bc022000"), ZlinkWire.encodeTouch(304, 316, false))
    }

    @Test fun keyEncodingMatchesWireFormat() {
        // KEYCODE_BACK = 4, down
        val down = ZlinkWire.encodeKey(4, true)
        assertArrayEquals(hex("ffffff100000000600000113080010041801"), down)
        // KEYCODE_BACK = 4, up
        val up = ZlinkWire.encodeKey(4, false)
        assertArrayEquals(hex("ffffff100000000600000113080010041800"), up)

        val f = ZlinkWire.readFeedFrame(feed(ZlinkWire.CH_CTRL_FROM_APP, down))
        assertEquals(ZlinkWire.CH_CTRL_FROM_APP, f.channel)
        assertEquals(ZlinkWire.TYPE_KEY, f.type)
        assertEquals(6, f.payload.size)
    }

    @Test fun forceKeyframeEncodingMatchesWireFormat() {
        val frame = ZlinkWire.encodeForceKeyframe()
        assertArrayEquals(hex("ffffff100000000000000d0b"), frame)
        val f = ZlinkWire.readFeedFrame(feed(ZlinkWire.CH_CTRL_FROM_APP, frame))
        assertEquals(ZlinkWire.CH_CTRL_FROM_APP, f.channel)
        assertEquals(ZlinkWire.TYPE_FORCE_KEYFRAME, f.type)
        assertEquals(0, f.payload.size)
    }

    private fun feed(channel: Int, frame: ByteArray): DataInputStream {
        val b = java.io.ByteArrayOutputStream()
        b.write(channel)
        b.write(byteArrayOf((frame.size ushr 24).toByte(), (frame.size ushr 16).toByte(), (frame.size ushr 8).toByte(), frame.size.toByte()))
        b.write(frame)
        return DataInputStream(ByteArrayInputStream(b.toByteArray()))
    }

    @Test fun feedFrameRoundTrip() {
        val frame = ZlinkWire.encodeTouch(10, 20, true)
        val f = ZlinkWire.readFeedFrame(feed(ZlinkWire.CH_CTRL_FROM_APP, frame))
        assertEquals(ZlinkWire.CH_CTRL_FROM_APP, f.channel)
        assertEquals(ZlinkWire.TYPE_TOUCH, f.type)
        assertEquals(frame.size - 12, f.payload.size)
    }

    @Test(expected = IOException::class) fun badMagicIsRejected() {
        val frame = ZlinkWire.encodeTouch(1, 1, true); frame[0] = 0
        ZlinkWire.readFeedFrame(feed(1, frame))
    }

    @Test fun videoUnitParsesHeaderAndNalType() {
        val payload = hex("000004000000026000000400000002600000000000000001" + "2764001f")
        val v = ZlinkWire.parseVideo(payload)!!
        assertEquals(1024, v.width); assertEquals(608, v.height); assertEquals(7, v.nalType)
        assertNull(ZlinkWire.parseVideo(ByteArray(24)))
    }

    @Test fun audioHeaderIsValidated() {
        val hdr = hex("0000ac4400000002000000100000000000000001")
        val ok = ZlinkWire.parseAudio(hdr.copyOf(24) + byteArrayOf(1, 2, 3, 4))!!
        assertEquals(44100, ok.sampleRate); assertEquals(2, ok.channels); assertEquals(4, ok.pcm.size)
        assertNull(ZlinkWire.parseAudio(ByteArray(30)))
    }

    @Test fun spsAndPpsInOneMessageAreSplit() {
        // Captured SPS message payload from the daemon: SPS, start code, PPS.
        val blob = hex("2764001fac1314501001369b80868303682211960000000128ee3cb0")
        val units = ZlinkWire.splitNalUnits(blob)
        assertEquals(2, units.size)
        assertEquals(7, units[0].first().toInt() and 0x1F); assertEquals(20, units[0].size)
        assertArrayEquals(hex("28ee3cb0"), units[1])
        assertEquals(1, ZlinkWire.splitNalUnits(hex("2764001f")).size)
        assertEquals(2, ZlinkWire.splitNalUnits(hex("27640000012800")).size)  // 3-byte start code
    }
}
