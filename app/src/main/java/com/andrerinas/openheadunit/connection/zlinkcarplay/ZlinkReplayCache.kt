package com.andrerinas.openheadunit.connection.zlinkcarplay

/**
 * Replay cache for late-subscribing CarPlay video decoders.
 *
 * Keeps the last SPS message (which also contains the PPS when sent together by the daemon),
 * any separate PPS message, the last IDR keyframe (NAL type 5) and all subsequent video frames.
 *
 * Total GOP size is capped at 48 MiB. When exceeded, the cache is invalidated until the next IDR.
 */
class ZlinkReplayCache(
    val maxGopBytes: Int = MAX_GOP_BYTES
) {
    private val lock = Any()
    private var sps: ZlinkWire.Frame? = null
    private var pps: ZlinkWire.Frame? = null
    private val gop = ArrayList<ZlinkWire.Frame>()
    private var gopBytes = 0
    private var gopValid = false

    val isValid: Boolean
        get() = synchronized(lock) { gopValid }

    val lastSps: ZlinkWire.Frame?
        get() = synchronized(lock) { sps }

    val lastPps: ZlinkWire.Frame?
        get() = synchronized(lock) { pps }

    val currentGopBytes: Int
        get() = synchronized(lock) { gopBytes }

    val frameCount: Int
        get() = synchronized(lock) { if (gopValid) gop.size else 0 }

    fun onVideoFrame(frame: ZlinkWire.Frame) {
        val payload = frame.payload
        val nal = if (payload.size > ZlinkWire.MEDIA_HEADER) {
            payload[ZlinkWire.MEDIA_HEADER].toInt() and 0x1F
        } else {
            -1
        }
        val frameBytes = ZlinkWire.FRAME_HEADER + payload.size

        synchronized(lock) {
            when (nal) {
                7 -> {
                    // The same settings sent again do not change what the cached keyframe and the frames after it
                    // decode to; only different ones (a new size, say) make the cached picture group useless.
                    val previous = sps
                    if (previous == null || !previous.payload.contentEquals(payload)) {
                        sps = frame
                        gopValid = false
                    }
                }
                8 -> {
                    pps = frame
                }
                5 -> {
                    gop.clear()
                    gopBytes = 0
                    sps?.let {
                        gop.add(it)
                        gopBytes += ZlinkWire.FRAME_HEADER + it.payload.size
                    }
                    pps?.let {
                        gop.add(it)
                        gopBytes += ZlinkWire.FRAME_HEADER + it.payload.size
                    }
                    gop.add(frame)
                    gopBytes += frameBytes
                    gopValid = true
                }
                else -> {
                    if (gopValid) {
                        gop.add(frame)
                        gopBytes += frameBytes
                        if (gopBytes > maxGopBytes) {
                            gop.clear()
                            gopBytes = 0
                            gopValid = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns a snapshot of the cached frames for replay to a new subscriber.
     * Returns an empty list if there is no valid GOP (e.g. before the first IDR or after exceeding the cap).
     */
    fun getReplayFrames(): List<ZlinkWire.Frame> = synchronized(lock) {
        if (!gopValid) emptyList() else ArrayList(gop)
    }

    fun reset(keepParameters: Boolean = true) = synchronized(lock) {
        if (!keepParameters) {
            sps = null
            pps = null
        }
        gop.clear()
        gopBytes = 0
        gopValid = false
    }

    companion object {
        const val MAX_GOP_BYTES = 48 shl 20 // 48 MiB
    }
}
