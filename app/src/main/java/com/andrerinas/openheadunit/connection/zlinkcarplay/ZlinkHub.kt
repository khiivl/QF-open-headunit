package com.andrerinas.openheadunit.connection.zlinkcarplay

import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process distribution hub for zLink CarPlay frames and control.
 *
 * Maintains the subscriber list, replay cache for late subscribers, session health monitoring,
 * and holds the daemon's wireless CarPlay offer while subscribers exist.
 */
object ZlinkHub {
    private val subIdSeq = AtomicInteger()
    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    val replayCache = ZlinkReplayCache()
    val holdPolicy = ZlinkHoldPolicy()
    private val _sessionStateFlow = MutableStateFlow(ZlinkSessionState.CONNECTED)
    val sessionStateFlow = _sessionStateFlow.asStateFlow()

    private val _hasActiveSessionFlow = MutableStateFlow(false)
    val hasActiveSessionFlow = _hasActiveSessionFlow.asStateFlow()

    @Volatile private var cachedSps: ZlinkWire.Frame? = null
    @Volatile private var cachedPps: ZlinkWire.Frame? = null

    @Volatile private var sessionActive = false

    /** True after a Disconnect or a mode switch, until the user connects CarPlay again. */
    val isOfferSuspended: Boolean
        get() = holdPolicy.offerSuspended

    val hasActiveSession: Boolean
        get() = !holdPolicy.offerSuspended && sessionActive && sessionMonitor.state == ZlinkSessionState.CONNECTED

    val sessionMonitor = ZlinkSessionMonitor(
        onStateChanged = {
            AppLog.i("zlink hub: session state changed to $it")
            _sessionStateFlow.value = it
            if (it == ZlinkSessionState.GAVE_UP) {
                sessionActive = false
                replayCache.reset()
                videoSink?.release()
                videoSink = null
            }
            _hasActiveSessionFlow.value = hasActiveSession
            broadcastSessionState(it)
        }
    )

    @Volatile var videoSink: ZlinkVideoSink? = null
        private set

    fun getOrCreateVideoSink(surface: android.view.Surface? = null): ZlinkVideoSink {
        val existing = videoSink
        if (existing != null) {
            surface?.let { existing.attachSurface(it) }
            return existing
        }
        val sink = ZlinkVideoSink(surface)
        cachedSps?.let { spsFrame ->
            ZlinkWire.parseVideo(spsFrame.payload)?.let { sink.feed(it) }
        }
        cachedPps?.let { ppsFrame ->
            ZlinkWire.parseVideo(ppsFrame.payload)?.let { sink.feed(it) }
        }
        videoSink = sink
        return sink
    }

    /** The screen's surface; the sink draws its newest picture onto it at once, so nothing is replayed. */
    fun attachSurface(surface: android.view.Surface): Boolean {
        val existing = videoSink
        if (existing == null) getOrCreateVideoSink(surface) else existing.attachSurface(surface)
        return false
    }

    fun detachSurface() {
        videoSink?.detachSurface()
    }

    @Volatile private var daemonCtrlOut: OutputStream? = null
    @Volatile private var daemonCtrlLock = Any()

    // Writes to the daemon control socket run off-thread so touches from the UI thread never block.
    // Elevated to URGENT_DISPLAY priority so touch events are dispatched without scheduler latency.
    private val txExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Exception) {}
            r.run()
        }, "zlink-ctrl-tx").also { it.isDaemon = true }
    }
    private var scheduler: ScheduledExecutorService? = null

    init {
        // Ticks session monitor every 500ms to detect heartbeat timeouts and gave-up state
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "zlink-session-monitor").also { it.isDaemon = true }
        }.scheduleWithFixedDelay({
            try {
                sessionMonitor.tick()
            } catch (e: Exception) {
                AppLog.w("zlink hub: session monitor tick error: ${e.message}")
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    enum class EndReason {
        USER,
        POWER
    }

    interface Listener {
        fun onFrame(frame: ZlinkWire.Frame) {}
        fun onReplayEnd() {}
        fun onSessionState(state: ZlinkSessionState) {}
    }

    private object ReplayEndMarker

    private class Subscriber(
        val listener: Listener,
        val id: Int,
        private val onDrop: (Subscriber) -> Unit
    ) : Closeable {
        val queue = ArrayBlockingQueue<Any>(400)
        @Volatile var closed = false

        private val worker = Thread({
            while (!closed) {
                val item = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (closed) break
                try {
                    when (item) {
                        is ZlinkWire.Frame -> listener.onFrame(item)
                        is ReplayEndMarker -> {
                            listener.onFrame(ZlinkWire.Frame(ZlinkWire.CH_REPLAY_END, 0, ByteArray(0)))
                            listener.onReplayEnd()
                        }
                        is ZlinkSessionState -> listener.onSessionState(item)
                    }
                } catch (e: Exception) {
                    AppLog.w("zlink hub: subscriber $id error: ${e.message}")
                }
            }
        }, "zlink-sub-$id").also {
            it.isDaemon = true
            it.start()
        }

        fun offer(item: Any): Boolean {
            if (closed) return false
            val ok = queue.offer(item)
            if (!ok) {
                AppLog.w("zlink hub: subscriber $id too slow, dropping")
                close()
                onDrop(this)
            }
            return ok
        }

        override fun close() {
            if (closed) return
            closed = true
            worker.interrupt()
        }
    }

    val subscriberCount: Int
        get() = subscribers.size

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    /**
     * Subscribes a listener to the CarPlay feed. Replays the cached GOP to the subscriber
     * before delivering live frames.
     */
    fun subscribe(listener: Listener, userIntent: Boolean = true, replay: Boolean = true): Closeable {
        // Opening the CarPlay screen indicates explicit intent to view CarPlay; cancel any suspension and resume.
        // The always-on service subscription is not an intent: it must not undo a Disconnect or a mode switch.
        if (userIntent) resumeOffer()
        val oldCount = subscribers.size
        val id = subIdSeq.incrementAndGet()
        lateinit var sub: Subscriber
        sub = Subscriber(listener, id) { dropped ->
            removeSubscriber(dropped)
        }

        // Always deliver SPS and PPS first so new subscriber's decoder is configured immediately
        (cachedSps ?: replayCache.lastSps)?.let { sub.offer(it) }
        (cachedPps ?: replayCache.lastPps)?.let { sub.offer(it) }

        // Replay cached GOP. A listener that only watches the feed (the CarPlay screen: the picture is decoded by the
        // sink, not by it) skips this; thousands of frames would overflow its queue and get it dropped.
        val replayFrames = if (replay) replayCache.getReplayFrames() else emptyList()
        if (replayFrames.isNotEmpty()) {
            for (f in replayFrames) {
                sub.offer(f)
            }
        }
        requestKeyframe()
        sub.offer(ReplayEndMarker)
        sub.offer(sessionMonitor.state)

        subscribers.add(sub)
        val newCount = subscribers.size
        onSubscriberCountChanged(oldCount, newCount)

        return Closeable {
            removeSubscriber(sub)
        }
    }

    private fun removeSubscriber(sub: Subscriber) {
        val oldCount = subscribers.size
        if (subscribers.remove(sub)) {
            sub.close()
            val newCount = subscribers.size
            onSubscriberCountChanged(oldCount, newCount)
        }
    }

    private fun onSubscriberCountChanged(oldCount: Int, newCount: Int) {
        val frameToSend = holdPolicy.onSubscriberCountChanged(oldCount, newCount)
        if (frameToSend != null) {
            writeToDaemon(frameToSend)
        }
    }

    fun setDaemonControl(out: OutputStream?, lock: Any) {
        daemonCtrlOut = out
        daemonCtrlLock = lock
        // If the daemon control channel is connected and the offer is not suspended (a user Disconnect stays off even
        // though the service itself is always subscribed),
        // offer mode 2 immediately.
        if (out != null && !holdPolicy.offerSuspended) {
            val toSend = holdPolicy.resumeOffer(subscribers.size)
            if (toSend != null) {
                writeToDaemon(toSend)
            }
            if (subscribers.isNotEmpty()) {
                requestKeyframe()
            }
        }
    }

    fun onDaemonControlClosed(expectedOut: OutputStream? = null) {
        if (expectedOut == null || daemonCtrlOut === expectedOut) {
            daemonCtrlOut = null
            sessionActive = false
            _hasActiveSessionFlow.value = false
            sessionMonitor.onDaemonSocketClosed()
        }
    }

    /**
     * Injects a touch event toward the daemon on the control channel (port 1777).
     * Safe to call from any thread, including the UI thread.
     */
    fun injectTouch(x: Int, y: Int, down: Boolean) {
        val frame = ZlinkWire.encodeTouch(x, y, down)
        inject(frame)
    }

    /**
     * Injects a physical key event toward the daemon on the control channel (port 1777).
     * Safe to call from any thread, including the UI thread.
     */
    fun injectKey(keyCode: Int, down: Boolean) {
        AppLog.i("zlink hub: injectKey keyCode=$keyCode down=$down")
        val frame = ZlinkWire.encodeKey(keyCode, down)
        inject(frame)
    }

    /**
     * Requests an immediate IDR keyframe from the CarPlay daemon (opcode 0xd0b).
     * Safe to call from any thread.
     */
    fun requestKeyframe() {
        // Intentionally does nothing. Opcode 0xd0b is the daemon's MFi (Apple authentication chip) info request,
        // not a keyframe request, and sending it repeatedly disturbed the session. The decoder waits for the next
        // IDR instead (see ZlinkVideoSink's watchdog and fallback).
    }

    /**
     * Injects a raw frame toward the daemon. Only touch (0x111), key (0x113), test (0x705), and force keyframe (0xd0b) frames are allowed.
     */
    fun inject(frame: ByteArray) {
        if (frame.size < ZlinkWire.FRAME_HEADER ||
            frame[0] != (-1).toByte() || frame[1] != (-1).toByte() ||
            frame[2] != (-1).toByte() || frame[3] != 16.toByte()
        ) return

        val ty = ZlinkWire.be32(frame, 8)
        if (ty != ZlinkMessage.TYPE_TOUCH && ty != ZlinkMessage.TYPE_KEY && ty != ZlinkMessage.TYPE_TEST_705 && ty != ZlinkWire.TYPE_FORCE_KEYFRAME) return

        try {
            txExecutor.execute {
                writeToDaemonUnderLock(frame)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    fun writeToDaemon(frame: ByteArray) {
        try {
            txExecutor.execute {
                writeToDaemonUnderLock(frame)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun writeToDaemonUnderLock(frame: ByteArray) {
        val o = daemonCtrlOut
        if (o == null) {
            AppLog.w("zlink hub: writeToDaemonUnderLock dropped frame (daemonCtrlOut is null)")
            return
        }
        try {
            synchronized(daemonCtrlLock) {
                o.write(frame)
                o.flush()
            }
        } catch (e: IOException) {
            AppLog.w("zlink hub: write to daemon failed: ${e.message}")
        }
    }

    /**
     * Inspects 0x102 messages from the app to the daemon, applying hold mode rewriting if needed.
     */
    fun onAppInit(frame: ByteArray): ByteArray {
        AppLog.i("zlink hub: init from the app: ${ZlinkMessage.describeInit(frame)}")
        return holdPolicy.onAppInitFrame(frame, subscribers.size)
    }

    /**
     * Publishes a raw frame received by the proxy pumps to all subscribers.
     */
    // What the zLink app sends the daemon on the control channel, for working out which message a hardware button
    // becomes (the voice button opens Siri in zLink's own screen). Every key message (0x113) is logged; any other type
    // the first time it is seen, with the start of its payload. Touch (0x111) and the heartbeat (0x118) are skipped.
    private val seenAppControlTypes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private fun noteAppControl(type: Int, payload: ByteArray) {
        if (type == 0x111 || type == 0x118) return
        if (type == ZlinkWire.TYPE_KEY) {
            AppLog.i("zlink app->daemon key message: ${payload.take(16).joinToString(" ") { "%02x".format(it) }}")
            return
        }
        if (seenAppControlTypes.add(type)) {
            AppLog.i("zlink app->daemon first control type 0x${Integer.toHexString(type)} len=${payload.size} start=" +
                payload.take(24).joinToString(" ") { "%02x".format(it) })
        }
    }

    fun publish(port: Int, d2a: Boolean, type: Int, payload: ByteArray) {
        val ch = when {
            port == 1888 && d2a -> ZlinkWire.CH_VIDEO
            port == 1666 && d2a -> ZlinkWire.CH_AUDIO
            port == 1777 && d2a -> ZlinkWire.CH_CTRL_FROM_DAEMON
            port == 1777 && !d2a -> ZlinkWire.CH_CTRL_FROM_APP
            else -> 0
        }
        if (ch == 0) return

        if (port == 1777 && !d2a) noteAppControl(type, payload)

        if (holdPolicy.offerSuspended) {
            // Drop trailing media frames while offer is suspended so session monitor/state is not revived
            if (ch == ZlinkWire.CH_VIDEO || ch == ZlinkWire.CH_AUDIO) {
                return
            }
        }

        val frame = ZlinkWire.Frame(ch, type, payload)

        // Feed session health monitor
        if (port == 1777 && d2a && type == 0x118) {
            sessionMonitor.onHeartbeat()
        } else if (port == 1888 && d2a) {
            val nal = if (payload.size > ZlinkWire.MEDIA_HEADER) {
                payload[ZlinkWire.MEDIA_HEADER].toInt() and 0x1F
            } else {
                -1
            }
            if (nal == 7) {
                cachedSps = frame
                cachedPps = frame
                sessionMonitor.onSps()
            } else if (nal == 8) {
                cachedPps = frame
            } else {
                sessionMonitor.onMediaFrame()
            }
        } else if (port == 1666 && d2a) {
            sessionMonitor.onMediaFrame()
        }

        if (ch == ZlinkWire.CH_VIDEO) {
            replayCache.onVideoFrame(frame)
            if (type == ZlinkWire.TYPE_VIDEO) {
                ZlinkWire.parseVideo(payload)?.let { unit ->
                    videoSink?.feed(unit)
                }
            }
        }

        if (ch == ZlinkWire.CH_VIDEO || ch == ZlinkWire.CH_AUDIO) {
            if (!sessionActive) {
                sessionActive = true
                _hasActiveSessionFlow.value = hasActiveSession
                _sessionStateFlow.value = ZlinkSessionState.CONNECTED
            }
        }

        if (subscribers.isEmpty()) return

        for (sub in subscribers) {
            sub.offer(frame)
        }
    }

    /**
     * Broadcasts a session state update (from WP4 session monitor) to all subscribers.
     */
    fun broadcastSessionState(state: ZlinkSessionState) {
        for (sub in subscribers) {
            sub.offer(state)
        }
    }

    /**
     * Ends the current CarPlay session (WP3/WP5).
     * Suspends the offer and sends field 7 = 0 to the daemon.
     */
    fun endSession(reason: EndReason) {
        val frame = holdPolicy.suspendOffer()
        writeToDaemon(frame)
        sessionActive = false
        _hasActiveSessionFlow.value = false
        replayCache.reset()
        videoSink?.release()
        videoSink = null
        sessionMonitor.markGaveUp()
        // Link mode 0 only stops offering CarPlay; a session that is already up keeps running and the unit's Bluetooth
        // screen stays locked. Dropping the daemon connections makes it restart, which really ends the session.
        proxyDropper?.invoke()
        // A user Disconnect stays disconnected until CarPlay is connected again (Connect button, opening the CarPlay
        // screen, or ACC returning); nothing resumes it on a timer.
    }

    /**
     * Keeps CarPlay from connecting on its own, for a switch into CarPlay mode: it starts only when the user
     * connects it (Connect button or opening the CarPlay screen). Nothing is torn down here.
     */
    fun holdOffUntilConnect() {
        writeToDaemon(holdPolicy.suspendOffer())
    }

    @Volatile private var proxyDropper: (() -> Unit)? = null

    fun setProxyDropper(dropper: (() -> Unit)?) { proxyDropper = dropper }

    /**
     * [force]: restart the daemon link even if the offer was not suspended. A fresh Connect needs it: the unit's
     * Bluetooth module may still be set up for Android Auto, and only a daemon restart sets it up for CarPlay again.
     */
    fun resumeOffer(force: Boolean = false) {
        // Only an offer that was actually suspended (user Disconnect, ACC off) needs the daemon restarted. The
        // CarPlay screen also calls this on every resume, and that must never disturb a live session.
        val wasSuspended = holdPolicy.offerSuspended || force
        synchronized(this) {
            scheduler?.shutdownNow()
            scheduler = null
        }
        sessionMonitor.reset()
        holdPolicy.resumeOffer(subscribers.size)
        // Re-arming the offer alone does not bring the phone back. Dropping the daemon's connections makes it
        // run its own session end and restart, which is what reconnects the phone by itself.
        if (wasSuspended) {
            AppLog.i("zlink hub: resuming, dropping daemon connections so the daemon restarts and the phone reconnects")
            proxyDropper?.invoke()
        }
        if (subscribers.isNotEmpty()) {
            requestKeyframe()
        }
    }
}
