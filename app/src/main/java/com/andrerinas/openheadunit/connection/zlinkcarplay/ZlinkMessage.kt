package com.andrerinas.openheadunit.connection.zlinkcarplay

/**
 * Pure protobuf and framing logic for zLink control messages (channel 1777),
 * notably the 0x102 initialization and link-mode offer message.
 */
object ZlinkMessage {
    const val TYPE_INIT = 0x102
    /** The frame rate of the head unit's screen in a 0x102 message. */
    const val FIELD_FPS = 4
    const val TYPE_TOUCH = 0x111
    const val TYPE_KEY = 0x113
    const val TYPE_TEST_705 = 0x705
    const val TYPE_FORCE_KEYFRAME = 0xd0b

    /** The top-level varint fields of a 0x102 message as "field=value" pairs, for the log; other fields are left out. */
    fun describeInit(frame: ByteArray): String {
        val out = StringBuilder()
        var i = ZlinkWire.FRAME_HEADER
        while (i < frame.size) {
            var tag = 0L
            var sh = 0
            var b: Int
            do {
                if (i >= frame.size) return out.toString()
                b = frame[i++].toInt() and 0xFF
                tag = tag or ((b and 0x7F).toLong() shl sh)
                sh += 7
            } while ((b and 0x80) != 0)
            val fn = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> {
                    var v = 0L
                    sh = 0
                    do {
                        if (i >= frame.size) return out.toString()
                        b = frame[i++].toInt() and 0xFF
                        v = v or ((b and 0x7F).toLong() shl sh)
                        sh += 7
                    } while ((b and 0x80) != 0)
                    out.append(fn).append('=').append(v).append(' ')
                }
                2 -> {
                    var n = 0L
                    sh = 0
                    do {
                        if (i >= frame.size) return out.toString()
                        b = frame[i++].toInt() and 0xFF
                        n = n or ((b and 0x7F).toLong() shl sh)
                        sh += 7
                    } while ((b and 0x80) != 0)
                    i += n.toInt()
                }
                else -> return out.toString()
            }
        }
        return out.toString().trim()
    }

    /**
     * Reads the value of a top-level varint field in a 0x102 message payload, or -1 if not found.
     * Starts after the 12-byte zLink frame header.
     */
    fun fieldValue(frame: ByteArray, field: Int): Long {
        var i = ZlinkWire.FRAME_HEADER
        while (i < frame.size) {
            var tag = 0L
            var sh = 0
            var b: Int
            do {
                if (i >= frame.size) return -1L
                b = frame[i++].toInt() and 0xFF
                tag = tag or ((b and 0x7F).toLong() shl sh)
                sh += 7
            } while ((b and 0x80) != 0)

            val fn = (tag ushr 3).toInt()
            val wt = (tag and 7).toInt()
            when (wt) {
                0 -> {
                    var v = 0L
                    sh = 0
                    do {
                        if (i >= frame.size) return -1L
                        b = frame[i++].toInt() and 0xFF
                        v = v or ((b and 0x7F).toLong() shl sh)
                        sh += 7
                    } while ((b and 0x80) != 0)
                    if (fn == field) return v
                }
                2 -> {
                    var n = 0L
                    sh = 0
                    do {
                        if (i >= frame.size) return -1L
                        b = frame[i++].toInt() and 0xFF
                        n = n or ((b and 0x7F).toLong() shl sh)
                        sh += 7
                    } while ((b and 0x80) != 0)
                    i += n.toInt()
                }
                else -> return -1L
            }
        }
        return -1L
    }

    /**
     * Returns a copy of [frame] with field 7 set to [mode] (single-byte varint).
     * If field 7 is not present or cannot be rewritten in place, returns the original frame.
     */
    fun withVarint(frame: ByteArray, field: Int, value: Int): ByteArray {
        val c = frame.clone()
        var i = ZlinkWire.FRAME_HEADER
        while (i < c.size) {
            var tag = 0L
            var sh = 0
            var b: Int
            do {
                if (i >= c.size) return frame
                b = c[i++].toInt() and 0xFF
                tag = tag or ((b and 0x7F).toLong() shl sh)
                sh += 7
            } while ((b and 0x80) != 0)

            val fn = (tag ushr 3).toInt()
            val wt = (tag and 7).toInt()
            when (wt) {
                0 -> {
                    val start = i
                    do {
                        if (i >= c.size) return frame
                        b = c[i++].toInt() and 0xFF
                    } while ((b and 0x80) != 0)
                    if (fn == field && i - start == 1 && value in 0..127) {
                        c[start] = value.toByte()
                        return c
                    }
                }
                2 -> {
                    var n = 0L
                    sh = 0
                    do {
                        if (i >= c.size) return frame
                        b = c[i++].toInt() and 0xFF
                        n = n or ((b and 0x7F).toLong() shl sh)
                        sh += 7
                    } while ((b and 0x80) != 0)
                    i += n.toInt()
                }
                else -> return frame
            }
        }
        return frame
    }

    fun withMode(frame: ByteArray, mode: Int): ByteArray = withVarint(frame, 7, mode)
}
