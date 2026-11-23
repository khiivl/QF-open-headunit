package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Minimal H.264 decoder for the zLink video feed. The stream carries bare NAL units; this adds start
 * codes, configures the codec from the first SPS/PPS pair, and starts feeding at the first keyframe.
 *
 * Owned in the service/hub and kept warm across activity lifecycle changes. The decoder draws into a
 * [ZlinkSurfaceBridge] that outlives the screen; [attachSurface] and [detachSurface] only move the bridge's output.
 *
 * Preferred decoder is the hardware decoder (e.g. OMX.sprd.h264.decoder), falling back to Google's
 * software decoder (c2.android.avc.decoder) only if the hardware decoder stalls without producing output.
 */
class ZlinkVideoSink(initialSurface: Surface? = null) {
    private var bridge: ZlinkSurfaceBridge? = null
    @Volatile private var pendingSurface: Surface? = initialSurface
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = cachedSps
    private var pps: ByteArray? = cachedPps
    private var width = cachedWidth
    private var height = cachedHeight
    @Volatile private var running = true
    // Frames sent while the proxy replays the session so far only rebuild the decoder; they are not drawn.
    @Volatile private var live = false
    @Volatile private var mutedUntilMs = System.currentTimeMillis() + REPLAY_MUTE_FALLBACK_MS
    private var outputThread: Thread? = null

    @Volatile var decodedCount = 0L
        private set
    @Volatile var feedCount = 0L
        private set
    @Volatile private var firstFeedMs = 0L
    @Volatile private var lastStartFailureMs = 0L
    @Volatile var forceSoftware = false
        private set

    /** The proxy has finished replaying; draw everything from here on. */
    fun markLive() { live = true; bridge?.poke() }

    private fun shouldRender() = live || System.currentTimeMillis() > mutedUntilMs

    /** The screen's surface. The decoder never sees it: the bridge copies the newest picture onto it. */
    fun attachSurface(surface: Surface) {
        pendingSurface = surface
        bridge?.setOutput(surface)
    }

    fun detachSurface() {
        pendingSurface = null
        bridge?.setOutput(null)
        AppLog.i("zlink video: detached surface, the picture stays in the bridge")
    }

    private val feedLock = Any()

    fun feed(unit: ZlinkWire.VideoUnit) = synchronized(feedLock) { feedLocked(unit) }

    private fun feedLocked(unit: ZlinkWire.VideoUnit) {
        when (unit.nalType) {
            7 -> {
                // The daemon sends SPS and PPS together in one message, split by a start code.
                val parts = ZlinkWire.splitNalUnits(unit.nal)
                sps = parts.firstOrNull { (it[0].toInt() and 0x1F) == 7 }
                parts.firstOrNull { (it[0].toInt() and 0x1F) == 8 }?.let { pps = it }
                width = unit.width; height = unit.height
                cachedSps = sps
                cachedPps = pps
                cachedWidth = width
                cachedHeight = height
                return
            }
            8 -> {
                pps = unit.nal
                cachedPps = pps
                return
            }
        }

        feedCount++
        bytesCount += unit.nal.size
        // A session starts with a few blank keyframes (about 150 bytes) and tiny frames; the first one big enough to
        // be the interface is the picture, and the connecting screen must stay until that one has been shown.
        if (contentFrameIndex == 0L && unit.nal.size >= CONTENT_FRAME_BYTES) contentFrameIndex = feedCount
        if (firstFeedMs == 0L) firstFeedMs = System.currentTimeMillis()

        // Hardware decoder stall watchdog: if hardware decoder receives frames for > 5s but produces 0 output frames, fall back to software
        if (!forceSoftware && feedCount > 90 && decodedCount == 0L && (System.currentTimeMillis() - firstFeedMs) > STALL_TIMEOUT_MS) {
            AppLog.w("zlink video: hardware decoder stalled with 0 output after ${feedCount} frames (${STALL_TIMEOUT_MS}ms), falling back to software decoder")
            forceSoftware = true
            restartCodec()
            return
        }

        if (codec == null) {
            if (System.currentTimeMillis() - lastStartFailureMs < 1000L) return
            val s = sps ?: return
            val p = pps ?: return
            try {
                startCodec(s, p)
            } catch (e: Exception) {
                lastStartFailureMs = System.currentTimeMillis()
                AppLog.e("zlink video: decoder start failed: ${e.javaClass.simpleName}: ${e.message}")
                codec = null
                return
            }
        }
        val c = codec ?: return
        try {
            // A dropped frame breaks every later P-frame, so wait for a buffer instead of skipping the frame.
            var index = -1
            val deadline = System.currentTimeMillis() + INPUT_WAIT_MS
            while (index < 0 && running && System.currentTimeMillis() < deadline) index = c.dequeueInputBuffer(20_000)
            if (index < 0) {
                AppLog.w("zlink video: no input buffer for ${INPUT_WAIT_MS}ms, flushing decoder")
                c.flush()
                return
            }
            val buf = c.getInputBuffer(index) ?: return
            buf.clear()
            if (buf.capacity() < unit.nal.size + 4) {
                c.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }
            buf.put(START_CODE).put(unit.nal)
            c.queueInputBuffer(index, 0, unit.nal.size + 4, System.nanoTime() / 1000, 0)
        } catch (e: Exception) {
            AppLog.w("zlink video: feed failed: ${e.message}")
        }
    }

    private fun createDecoder(): MediaCodec {
        if (!forceSoftware) {
            try {
                val hw = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                AppLog.i("zlink video: created preferred hardware decoder: ${hw.name}")
                return hw
            } catch (e: Exception) {
                AppLog.w("zlink video: hardware decoder creation failed: ${e.message}")
            }
        }
        val candidates = listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        for (name in candidates) {
            try {
                val sw = MediaCodec.createByCodecName(name)
                AppLog.i("zlink video: created software decoder fallback: $name")
                return sw
            } catch (_: Exception) {}
        }
        AppLog.i("zlink video: falling back to default decoder for video/avc")
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    private fun startCodec(s: ByteArray, p: ByteArray) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(START_CODE + s))
        format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(START_CODE + p))
        // Realtime interactive decoding flags
        try { format.setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Exception) {}
        try { format.setInteger("low-latency", 1) } catch (_: Exception) {}
        try { format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) } catch (_: Exception) {}
        val c = createDecoder()
        val b = bridge ?: ZlinkSurfaceBridge(width, height, { shouldRender() }, { renderedCount++ }).also {
            bridge = it
            it.setOutput(pendingSurface)
        }
        c.configure(format, b.inputSurface, null, 0)
        c.start()
        codec = c
        AppLog.i("zlink video: decoder ${c.name} started ${width}x$height (isHardware=${!forceSoftware}) with low-latency flags")
        outputThread = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Exception) {}
            val info = MediaCodec.BufferInfo()
            while (running) {
                try {
                    val i = c.dequeueOutputBuffer(info, 10_000L)
                    if (i >= 0) {
                        decodedCount++
                        // Every picture goes to the bridge, which keeps the newest and draws it when allowed.
                        c.releaseOutputBuffer(i, true)
                    }
                    logStats()
                } catch (e: IllegalStateException) {
                    if (!running) break
                } catch (e: Exception) {
                    if (running) AppLog.w("zlink video: output ended: ${e.message}")
                    break
                }
            }
        }, "zlink-video-out").also { it.isDaemon = true; it.start() }
    }

    // Once per STATS_INTERVAL_MS: how many frames came in, were decoded and were shown, per second.
    /** Frames shown on the surface so far; the CarPlay screen keeps its connecting screen up until this is above 0. */
    @Volatile var renderedCount = 0L
        private set

    /** Which frame of the session (1-based) was the first with real content in it, or 0 before that one arrived. */
    @Volatile var contentFrameIndex = 0L
        private set
    @Volatile private var bytesCount = 0L
    private var statsAtMs = 0L
    private var statsFed = 0L
    private var statsDecoded = 0L
    private var statsRendered = 0L
    private var statsBytes = 0L

    private fun logStats() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (statsAtMs == 0L) { statsAtMs = now; statsFed = feedCount; statsDecoded = decodedCount; statsRendered = renderedCount; statsBytes = bytesCount; return }
        val elapsed = now - statsAtMs
        if (elapsed < STATS_INTERVAL_MS) return
        val fed = feedCount - statsFed
        val dec = decodedCount - statsDecoded
        val rend = renderedCount - statsRendered
        val bytes = bytesCount - statsBytes
        fun fps(n: Long) = n * 1000f / elapsed
        AppLog.i(String.format(java.util.Locale.US,
            "zlink video: %dx%d over %dms: in=%.1f fps, decoded=%.1f fps, shown=%.1f fps, %.0f kbit/s",
            width, height, elapsed, fps(fed), fps(dec), fps(rend), bytes * 8f / elapsed))
        statsAtMs = now; statsFed = feedCount; statsDecoded = decodedCount; statsRendered = renderedCount; statsBytes = bytesCount
    }

    private fun restartCodec() {
        running = false
        outputThread?.join(300)
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        running = true
        val s = sps
        val p = pps
        if (s != null && p != null) {
            try {
                startCodec(s, p)
            } catch (e: Exception) {
                AppLog.e("zlink video: restartCodec failed: ${e.message}")
            }
        }
    }

    fun release() {
        running = false
        outputThread?.join(300)
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        bridge?.release()
        bridge = null
        decodedCount = 0L
        renderedCount = 0L
        contentFrameIndex = 0L
        feedCount = 0L
        firstFeedMs = 0L
        forceSoftware = false
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val INPUT_WAIT_MS = 1000L
        private const val REPLAY_MUTE_FALLBACK_MS = 3000L   // an older proxy sends no replay-end marker
        private const val STALL_TIMEOUT_MS = 5000L
        private const val REDRAW_MUTE_MS = 8000L   // longest a redraw keeps the picture muted
        private const val STATS_INTERVAL_MS = 5000L
        private const val CONTENT_FRAME_BYTES = 3000   // smaller than this is a blank or near-static frame

        @Volatile var cachedSps: ByteArray? = null
        @Volatile var cachedPps: ByteArray? = null
        @Volatile var cachedWidth: Int = 1024
        @Volatile var cachedHeight: Int = 608
    }
}
