package com.andrerinas.openheadunit.connection

import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.SystemProperties

/**
 * CoD OS: Open Headunit tells the system a session is up or down the way Z-Link does: the same broadcast, with the same
 * words, and nothing else.
 *
 * Contract: `cod/OPEN_HEADUNIT_A14_CONTRACT.md` section 10 (revision of 24.11, owner: «що розсилає і що вмикає Z-Link, те саме
 * маєш вмикати ти»). Z-Link announces its sessions with the broadcast `com.zjinnova.zlink`: `status` = `CONNECTED` / `DISCONNECT` and
 * `phoneMode` = `auto_wireless` / `auto_wired` for Android Auto. QF Bluetooth's own receiver turns that into everything the system
 * does for an Android Auto session, among them the flag `sys.qf.bt.aa.connect`. OHU writes no property itself: the flag has one
 * writer, QF Bluetooth, as it always had. Only one projection app is ever active (the projection switch), so the two never
 * announce at once.
 */
object ProjectionSessionPolicy {
    const val ACTION = "com.zjinnova.zlink"
    const val EXTRA_STATUS = "status"
    const val EXTRA_PHONE_MODE = "phoneMode"
    const val STATUS_CONNECTED = "CONNECTED"
    const val STATUS_DISCONNECT = "DISCONNECT"
    const val MODE_WIRELESS = "auto_wireless"
    const val MODE_WIRED = "auto_wired"

    /** The flag QF Bluetooth derives from the broadcast. Read only, to drop a stale value left by an earlier process. */
    const val FLAG = "sys.qf.bt.aa.connect"

    data class Message(val status: String, val phoneMode: String)

    /**
     * Up from the end of the SSL handshake: the phone is a working Android Auto phone and a session exists.
     * Connecting, Connected, StartingTransport, Error and Disconnected are down, so a phone that keeps failing its handshake
     * does not toggle the system.
     */
    fun isUp(state: CommManager.ConnectionState): Boolean =
        state is CommManager.ConnectionState.HandshakeComplete || state is CommManager.ConnectionState.TransportStarted

    /** A cable is `auto_wired`; anything else (the Wi-Fi link) is `auto_wireless`. */
    fun phoneMode(type: ProjectionConnection.Type?): String =
        if (type == ProjectionConnection.Type.USB) MODE_WIRED else MODE_WIRELESS

    fun message(up: Boolean, phoneMode: String): Message = Message(if (up) STATUS_CONNECTED else STATUS_DISCONNECT, phoneMode)
}

/**
 * Sends the message only when the answer changes. A disconnect carries the mode of the connect it ends, as Z-Link's does. If a
 * send fails the answer is not remembered, so the next state change tries again. Pure of Android and of the flavor: unit tested.
 */
class ProjectionSessionWriter(
    private val send: (ProjectionSessionPolicy.Message) -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val lock = Any()
    private var published: Boolean? = null
    private var mode: String = ProjectionSessionPolicy.MODE_WIRELESS

    /** [type] is needed for a connect; a disconnect uses the mode it announced. */
    fun apply(up: Boolean, type: ProjectionConnection.Type? = null) = synchronized(lock) {
        if (published == up) return@synchronized
        if (up) mode = ProjectionSessionPolicy.phoneMode(type)
        try {
            send(ProjectionSessionPolicy.message(up, mode))
            published = up
        } catch (t: Throwable) {
            onFailure(t)
        }
    }

    /**
     * A process that starts knows of no session. If an earlier process left the system believing one is up (a kill), the
     * disconnect Z-Link would have sent is sent now; otherwise nothing is sent.
     */
    fun startFresh(systemThinksUp: Boolean) = synchronized(lock) {
        published = false
        if (!systemThinksUp) return@synchronized
        try {
            send(ProjectionSessionPolicy.message(false, mode))
        } catch (t: Throwable) {
            onFailure(t)
        }
    }
}

/** The CoD build's instance: the broadcast goes out through the application context. */
object ProjectionSessionPublisher {
    @Volatile private var warned = false
    @Volatile private var appContext: Context? = null

    private val writer = ProjectionSessionWriter(
        send = { m ->
            val ctx = appContext ?: throw IllegalStateException("no context yet")
            ctx.sendBroadcast(
                Intent(ProjectionSessionPolicy.ACTION)
                    .putExtra(ProjectionSessionPolicy.EXTRA_STATUS, m.status)
                    .putExtra(ProjectionSessionPolicy.EXTRA_PHONE_MODE, m.phoneMode)
            )
            AppLog.i("ProjectionSession: ${m.status} ${m.phoneMode}")
        },
        onFailure = { t ->
            if (!warned) {
                warned = true
                AppLog.w("ProjectionSession: could not send the session broadcast: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    )

    /** Called from the one state setter of [CommManager]; every transition passes through it. */
    fun onState(context: Context, state: CommManager.ConnectionState, type: ProjectionConnection.Type?) {
        if (!BuildConfig.CODOS) return
        appContext = context.applicationContext
        writer.apply(ProjectionSessionPolicy.isUp(state), type)
    }

    /** A safety net for service stop: publishes what the state really is, so a stop never leaves a stale answer. */
    fun resync(context: Context, state: CommManager.ConnectionState) = onState(context, state, null)

    /** Process start: drops a session an earlier process left, and announces the end on an uncaught exception (best effort). */
    fun start(context: Context) {
        if (!BuildConfig.CODOS) return
        appContext = context.applicationContext
        writer.startFresh(SystemProperties.getBoolean(ProjectionSessionPolicy.FLAG, false))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { writer.apply(false) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }
}
