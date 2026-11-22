package com.andrerinas.openheadunit.connection.zlinkcarplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class ZlinkMessageTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun buildFrame(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        out[0] = 0xFF.toByte(); out[1] = 0xFF.toByte(); out[2] = 0xFF.toByte(); out[3] = 0x10
        val len = payload.size
        out[4] = (len ushr 24).toByte()
        out[5] = (len ushr 16).toByte()
        out[6] = (len ushr 8).toByte()
        out[7] = len.toByte()
        out[8] = (type ushr 24).toByte()
        out[9] = (type ushr 16).toByte()
        out[10] = (type ushr 8).toByte()
        out[11] = type.toByte()
        System.arraycopy(payload, 0, out, 12, len)
        return out
    }

    private fun encodeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun build0x102Payload(mode: Int, deviceName: String = "TestCar"): ByteArray {
        val baos = ByteArrayOutputStream()
        // field 1 (varint = 1): tag = (1 shl 3) | 0 = 0x08
        baos.write(0x08); encodeVarint(baos, 1)
        // field 2 (string, wt = 2): tag = (2 shl 3) | 2 = 0x12
        val nameBytes = deviceName.toByteArray()
        baos.write(0x12); encodeVarint(baos, nameBytes.size.toLong())
        baos.write(nameBytes)
        // field 7 (link_mode, wt = 0): tag = (7 shl 3) | 0 = 0x38
        baos.write(0x38); encodeVarint(baos, mode.toLong())
        // field 10 (varint = 100): tag = (10 shl 3) | 0 = 0x50
        baos.write(0x50); encodeVarint(baos, 100)
        return baos.toByteArray()
    }

    @Test fun readField7From0x102Message() {
        val payloadMode2 = build0x102Payload(2)
        val frameMode2 = buildFrame(0x102, payloadMode2)
        assertEquals(2L, ZlinkMessage.fieldValue(frameMode2, 7))
        assertEquals(1L, ZlinkMessage.fieldValue(frameMode2, 1))
        assertEquals(100L, ZlinkMessage.fieldValue(frameMode2, 10))
        assertEquals(-1L, ZlinkMessage.fieldValue(frameMode2, 99)) // non-existent

        val payloadMode0 = build0x102Payload(0)
        val frameMode0 = buildFrame(0x102, payloadMode0)
        assertEquals(0L, ZlinkMessage.fieldValue(frameMode0, 7))
    }

    @Test fun rewriteField7ToMode2AndMode0() {
        val frameMode0 = buildFrame(0x102, build0x102Payload(0))
        assertEquals(0L, ZlinkMessage.fieldValue(frameMode0, 7))

        val rewrittenTo2 = ZlinkMessage.withMode(frameMode0, 2)
        assertEquals(2L, ZlinkMessage.fieldValue(rewrittenTo2, 7))
        // Verify other fields are preserved
        assertEquals(1L, ZlinkMessage.fieldValue(rewrittenTo2, 1))
        assertEquals(100L, ZlinkMessage.fieldValue(rewrittenTo2, 10))

        val rewrittenTo0 = ZlinkMessage.withMode(rewrittenTo2, 0)
        assertEquals(0L, ZlinkMessage.fieldValue(rewrittenTo0, 7))
    }

    @Test fun malformedFrameReturnsMinusOne() {
        val truncated = hex("ffffff10000000020000010208") // incomplete varint tag
        assertEquals(-1L, ZlinkMessage.fieldValue(truncated, 7))

        val empty = hex("ffffff100000000000000102")
        assertEquals(-1L, ZlinkMessage.fieldValue(empty, 7))
    }
}
