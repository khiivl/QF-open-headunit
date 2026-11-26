package com.andrerinas.openheadunit.connection.zlinkcarplay

import java.io.DataInputStream
import java.io.IOException

/**
 * Wire format of the zLink daemon <-> app channels and frame parsing utilities.
 *
 * Every zLink frame is `ff ff ff 10 | u32 length | u32 type | payload` (big-endian).
 */
object ZlinkWire {
    const val CH_VIDEO = 1
    const val CH_AUDIO = 2
    const val CH_CTRL_FROM_DAEMON = 3
    const val CH_CTRL_FROM_APP = 4
    /** Sent once after the replay of the session so far; frames before it are history. */
    const val CH_REPLAY_END = 5

    const val TYPE_VIDEO = 0x302
    const val TYPE_AUDIO = 0x202
    const val TYPE_TOUCH = 0x111

    /** Daemon to app: the phone asks for the head unit's own screen (the car-maker tile on the CarPlay home screen). */
    const val TYPE_REQUEST_BACKGROUND = 0x501
    const val TYPE_KEY = 0x113
    const val TYPE_FORCE_KEYFRAME = 0xd0b

    const val FRAME_HEADER = 12
    const val MEDIA_HEADER = 24
    const val MAX_FRAME = 8 shl 20

    class Frame(val channel: Int, val type: Int, val payload: ByteArray)

    /** Reads one feed message, or throws [IOException] on EOF or a framing error. */
    fun readFeedFrame(input: DataInputStream): Frame {
        val channel = input.readUnsignedByte()
        val n = input.readInt()
        if (n < FRAME_HEADER || n > MAX_FRAME + FRAME_HEADER) throw IOException("bad feed length $n")
        val frame = ByteArray(n)
        input.readFully(frame)
        if (frame[0] != 0xFF.toByte() || frame[1] != 0xFF.toByte() || frame[2] != 0xFF.toByte() || frame[3] != 0x10.toByte()) {
            throw IOException("bad zLink magic")
        }
        val declared = be32(frame, 4)
        if (declared != n - FRAME_HEADER) throw IOException("zLink length $declared does not match feed length $n")
        return Frame(channel, be32(frame, 8), frame.copyOfRange(FRAME_HEADER, n))
    }

    fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    /** Video message payload: 24-byte header (w, h, w, h, 0, 1), then one NAL unit without a start code. */
    class VideoUnit(val width: Int, val height: Int, val nalType: Int, val nal: ByteArray)

    fun parseVideo(payload: ByteArray): VideoUnit? {
        if (payload.size <= MEDIA_HEADER) return null
        val nal = payload.copyOfRange(MEDIA_HEADER, payload.size)
        return VideoUnit(be32(payload, 0), be32(payload, 4), nal[0].toInt() and 0x1F, nal)
    }

    /**
     * Splits a buffer that holds several NAL units separated by 3- or 4-byte Annex-B start codes into
     * the units themselves. The daemon sends the SPS and the PPS in one message this way. A buffer
     * without a start code comes back as a single unit.
     */
    fun splitNalUnits(buf: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()   // (index of start code, its length)
        var i = 0
        while (i + 2 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                if (buf[i + 2].toInt() == 1) { starts.add(i to 3); i += 3; continue }
                if (i + 3 < buf.size && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1) { starts.add(i to 4); i += 4; continue }
            }
            i++
        }
        val units = ArrayList<ByteArray>()
        var from = 0
        for ((idx, len) in starts) {
            if (idx > from) units.add(buf.copyOfRange(from, idx))
            from = idx + len
        }
        if (from < buf.size) units.add(buf.copyOfRange(from, buf.size))
        return units
    }

    /** Audio message payload: 24-byte header (rate, channels, bits, ...), then raw PCM. */
    class AudioChunk(val sampleRate: Int, val channels: Int, val bits: Int, val pcm: ByteArray)

    fun parseAudio(payload: ByteArray): AudioChunk? {
        if (payload.size <= MEDIA_HEADER) return null
        val rate = be32(payload, 0)
        val ch = be32(payload, 4)
        val bits = be32(payload, 8)
        if (rate <= 0 || ch !in 1..2 || bits != 16) return null
        return AudioChunk(rate, ch, bits, payload.copyOfRange(MEDIA_HEADER, payload.size))
    }

    /**
     * One touch event as the app sends it to the daemon: protobuf `{1: {1: 0, 2: x, 3: y, 4: action}}`
     * where action is 1 while the finger is down or moving and 0 on release. Coordinates are in video
     * pixels. Returns the complete zLink frame.
     */
    fun encodeTouch(x: Int, y: Int, down: Boolean): ByteArray {
        // Fast direct byte encoding to eliminate stream allocations on high-rate touch streams
        val buf = ByteArray(32)
        buf[0] = 0xFF.toByte(); buf[1] = 0xFF.toByte(); buf[2] = 0xFF.toByte(); buf[3] = 0x10
        putBe32(buf, 8, TYPE_TOUCH)

        var p = 14
        val bodyStart = p
        buf[p++] = 0x08; buf[p++] = 0
        buf[p++] = 0x10
        var vx = x
        while (vx >= 0x80) { buf[p++] = ((vx and 0x7F) or 0x80).toByte(); vx = vx ushr 7 }
        buf[p++] = vx.toByte()
        buf[p++] = 0x18
        var vy = y
        while (vy >= 0x80) { buf[p++] = ((vy and 0x7F) or 0x80).toByte(); vy = vy ushr 7 }
        buf[p++] = vy.toByte()
        buf[p++] = 0x20
        buf[p++] = if (down) 1 else 0

        val bodyLen = p - bodyStart
        buf[12] = 0x0A
        buf[13] = bodyLen.toByte()
        val payloadLen = bodyLen + 2
        putBe32(buf, 4, payloadLen)

        val frame = ByteArray(FRAME_HEADER + payloadLen)
        System.arraycopy(buf, 0, frame, 0, frame.size)
        return frame
    }

    /**
     * One physical key event as the app sends it to the daemon:
     * protobuf {1: message_id (0), 2: key (keyCode), 3: is_down (1 if down else 0)}.
     * Returns the complete zLink frame with type 0x113.
     */
    fun encodeKey(keyCode: Int, down: Boolean): ByteArray {
        val msg = java.io.ByteArrayOutputStream()
        msg.write(0x08); msg.write(0)
        msg.write(0x10); writeVarint(msg, keyCode)
        msg.write(0x18); msg.write(if (down) 1 else 0)
        val payload = msg.toByteArray()
        val frame = ByteArray(FRAME_HEADER + payload.size)
        frame[0] = 0xFF.toByte(); frame[1] = 0xFF.toByte(); frame[2] = 0xFF.toByte(); frame[3] = 0x10
        putBe32(frame, 4, payload.size)
        putBe32(frame, 8, TYPE_KEY)
        System.arraycopy(payload, 0, frame, FRAME_HEADER, payload.size)
        return frame
    }

    /**
     * Builds a 0xd0b control frame sent to the daemon (port 1777) to request an immediate IDR keyframe
     * from CarPlay. In libzjL10001.so, message 0xd0b invokes CarPlay_RequestAltKeyFrame, which issues
     * AirPlayReceiverSessionForceKeyFrame to the connected iPhone.
     */
    fun encodeForceKeyframe(): ByteArray {
        val frame = ByteArray(FRAME_HEADER)
        frame[0] = 0xFF.toByte(); frame[1] = 0xFF.toByte(); frame[2] = 0xFF.toByte(); frame[3] = 0x10
        putBe32(frame, 4, 0)
        putBe32(frame, 8, TYPE_FORCE_KEYFRAME)
        return frame
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v)
    }

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
