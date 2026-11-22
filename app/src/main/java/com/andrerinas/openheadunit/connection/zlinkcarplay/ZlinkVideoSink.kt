package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Minimal H.264 decoder for the zLink video feed. The stream carries bare NAL units; this adds start
 * codes, configures the codec from the first SPS/PPS pair, and starts feeding at the first keyframe.
 *
 * Owned in the service/hub and kept warm across activity lifecycle changes. Output surface is dynamically
 * switched via [attachSurface] and [detachSurface] using [MediaCodec.setOutputSurface].
 *
 * Preferred decoder is the hardware decoder (e.g. OMX.sprd.h264.decoder), falling back to Google's
 * software decoder (c2.android.avc.decoder) only if the hardware decoder stalls without producing output.
 */
class ZlinkVideoSink(initialSurface: Surface? = null) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = cachedSps
    private var pps: ByteArray? = cachedPps
    private var width = cachedWidth
    private var height = cachedHeight
    @Volatile private var running = true
    // Frames sent while the proxy replays the session so far only rebuild the decoder; they are not drawn.
    @Volatile private var live = false
    private val mutedUntilMs = System.currentTimeMillis() + REPLAY_MUTE_FALLBACK_MS
    private var outputThread: Thread? = null

    @Volatile private var currentSurface: Surface? = initialSurface
    @Volatile private var isAttached: Boolean = initialSurface != null

    @Volatile var decodedCount = 0L
        private set
    @Volatile var feedCount = 0L
        private set
    @Volatile private var firstFeedMs = 0L
    @Volatile private var lastStartFailureMs = 0L
    @Volatile var forceSoftware = false
        private set

    /** The proxy has finished replaying; draw everything from here on. */
    fun markLive() { live = true }

    private fun shouldRender() = live || System.currentTimeMillis() > mutedUntilMs

    fun attachSurface(surface: Surface) {
        if (currentSurface === surface && isAttached) {
            return
        }
        currentSurface = surface
        isAttached = true
        val c = codec
        if (c != null) {
            try {
                c.setOutputSurface(surface)
                AppLog.i("zlink video: setOutputSurface succeeded on ${c.name}")
            } catch (e: Exception) {
                AppLog.w("zlink video: setOutputSurface failed on ${c.name}: ${e.message}, restarting decoder for new surface")
                restartCodec(surface)
            }
        } else {
            val s = sps
            val p = pps
            if (s != null && p != null && surface.isValid) {
                try {
                    startCodec(s, p, surface)
                } catch (e: Exception) {
                    lastStartFailureMs = System.currentTimeMillis()
                    AppLog.e("zlink video: startCodec failed on attach: ${e.message}")
                }
            }
        }
    }

    fun detachSurface() {
        isAttached = false
        currentSurface = null
        AppLog.i("zlink video: detached surface, decoder continues running warm in background")
    }

    private val feedLock = Any()
    @Volatile private var generation = 0

    fun feed(unit: ZlinkWire.VideoUnit) = synchronized(feedLock) { feedLocked(unit) }

    fun isCurrentSurface(surface: Surface): Boolean = currentSurface === surface && isAttached

    /**
     * A still CarPlay screen sends no frames, so after the output surface changes the new surface would stay
     * black until something moves. This decodes the cached picture group again from its keyframe; the
     * held-last-frame logic then draws the final picture.
     */
    fun redrawFrom(frames: List<ZlinkWire.Frame>) {
        if (frames.isEmpty()) return
        Thread({
            synchronized(feedLock) {
                val c = codec ?: return@synchronized
                try {
                    live = false
                    generation++
                    c.flush()
                    for (f in frames) ZlinkWire.parseVideo(f.payload)?.let { feedLocked(it) }
                    AppLog.i("zlink video: redrew ${frames.size} cached frames on the new surface")
                } catch (e: Exception) {
                    AppLog.w("zlink video: redraw failed: ${e.message}")
                } finally {
                    live = true
                }
            }
        }, "zlink-redraw").also { it.isDaemon = true }.start()
    }

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
        if (firstFeedMs == 0L) firstFeedMs = System.currentTimeMillis()

        // Hardware decoder stall watchdog: if hardware decoder receives frames for > 5s but produces 0 output frames, fall back to software
        if (!forceSoftware && feedCount > 90 && decodedCount == 0L && (System.currentTimeMillis() - firstFeedMs) > STALL_TIMEOUT_MS) {
            AppLog.w("zlink video: hardware decoder stalled with 0 output after ${feedCount} frames (${STALL_TIMEOUT_MS}ms), falling back to software decoder")
            forceSoftware = true
            currentSurface?.let { restartCodec(it) }
            return
        }

        if (codec == null) {
            if (System.currentTimeMillis() - lastStartFailureMs < 1000L) return
            val s = sps ?: return
            val p = pps ?: return
            val surf = currentSurface ?: return
            if (!surf.isValid) return
            try {
                startCodec(s, p, surf)
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

    private fun startCodec(s: ByteArray, p: ByteArray, surface: Surface) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(START_CODE + s))
        format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(START_CODE + p))
        val c = createDecoder()
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        AppLog.i("zlink video: decoder ${c.name} started ${width}x$height (isHardware=${!forceSoftware})")
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            // While the replay is muted the newest decoded frame is held back, not dropped. CarPlay sends
            // nothing for a still screen, so without it the picture would stay black until something moved.
            var pending = -1
            var gen = generation
            while (running) {
                try {
                    if (gen != generation) { pending = -1; gen = generation }
                    val i = c.dequeueOutputBuffer(info, 50_000)
                    if (i >= 0) {
                        decodedCount++
                        val surf = currentSurface
                        if (isAttached && surf != null && surf.isValid && shouldRender()) {
                            if (pending >= 0) { c.releaseOutputBuffer(pending, true); pending = -1 }
                            c.releaseOutputBuffer(i, true)
                        } else {
                            if (pending >= 0) c.releaseOutputBuffer(pending, false)
                            pending = i
                        }
                    } else if (pending >= 0 && isAttached && shouldRender() && currentSurface?.isValid == true) {
                        c.releaseOutputBuffer(pending, true)
                        pending = -1
                    }
                } catch (e: IllegalStateException) {
                    // A flush invalidates the buffer we were holding; carry on with the next frame.
                    pending = -1
                    if (!running) break
                } catch (e: Exception) {
                    if (running) AppLog.w("zlink video: output ended: ${e.message}")
                    break
                }
            }
        }, "zlink-video-out").also { it.isDaemon = true; it.start() }
    }

    private fun restartCodec(surface: Surface) {
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
                startCodec(s, p, surface)
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
        decodedCount = 0L
        feedCount = 0L
        firstFeedMs = 0L
        forceSoftware = false
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val INPUT_WAIT_MS = 1000L
        private const val REPLAY_MUTE_FALLBACK_MS = 3000L   // an older proxy sends no replay-end marker
        private const val STALL_TIMEOUT_MS = 5000L

        @Volatile var cachedSps: ByteArray? = null
        @Volatile var cachedPps: ByteArray? = null
        @Volatile var cachedWidth: Int = 1024
        @Volatile var cachedHeight: Int = 608
    }
}
