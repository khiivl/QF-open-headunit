package com.andrerinas.openheadunit.connection

import android.os.Process
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.AppLog

/**
 * CoD OS: what «an Android Auto session is up» means for the rest of the system, and how it is published.
 *
 * Contract: `cod/OPEN_HEADUNIT_A14_CONTRACT.md` section 10. Two system properties in the QF pattern:
 * `sys.qf.ohu.connect` ("true" / "false") and `sys.qf.ohu.pid` (the pid of the process that set it up). A reader counts the
 * session as active only if the flag is true AND that pid is alive and is this app, so a killed process never leaves it «up».
 * Nothing is broadcast: Z-Link's own action must not be imitated (QF Bluetooth answers it with an A2DP disconnect and a toast).
 */
object ProjectionSessionPolicy {
    const val PROP_CONNECT = "sys.qf.ohu.connect"
    const val PROP_PID = "sys.qf.ohu.pid"

    /**
     * Up from the end of the SSL handshake: the phone is a working Android Auto phone and a session exists.
     * Connecting, Connected, StartingTransport, Error and Disconnected are down, so a phone that keeps failing its handshake
     * does not toggle the system.
     */
    fun isUp(state: CommManager.ConnectionState): Boolean =
        state is CommManager.ConnectionState.HandshakeComplete || state is CommManager.ConnectionState.TransportStarted

    /** The pid goes first, so a reader never sees «true» with no pid; down writes only the flag. */
    fun writes(up: Boolean, pid: Int): List<Pair<String, String>> =
        if (up) listOf(PROP_PID to pid.toString(), PROP_CONNECT to "true") else listOf(PROP_CONNECT to "false")

    /** A process that starts knows of no session: whatever an earlier process left is dropped. */
    fun startWrites(): List<Pair<String, String>> = listOf(PROP_CONNECT to "false", PROP_PID to "")
}

/**
 * Writes the two properties through [setter], only when the published answer changes. If a write fails the answer is not
 * remembered, so the next state change tries again. Pure of Android and of the flavor, so it is unit tested.
 */
class ProjectionSessionWriter(
    private val pid: () -> Int,
    private val setter: (String, String) -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val lock = Any()
    private var published: Boolean? = null

    fun apply(up: Boolean) = synchronized(lock) {
        if (published == up) return@synchronized
        try {
            ProjectionSessionPolicy.writes(up, pid()).forEach { (k, v) -> setter(k, v) }
            published = up
        } catch (t: Throwable) {
            onFailure(t)
        }
    }

    fun clearAtStart() = synchronized(lock) {
        try {
            ProjectionSessionPolicy.startWrites().forEach { (k, v) -> setter(k, v) }
            published = false
        } catch (t: Throwable) {
            onFailure(t)
        }
    }
}

/** The CoD build's instance: system property writes through the hidden `android.os.SystemProperties.set`. */
object ProjectionSessionPublisher {
    @Volatile private var warned = false

    private val writer = ProjectionSessionWriter(
        pid = { Process.myPid() },
        setter = ::setProperty,
        onFailure = { t ->
            if (!warned) {
                warned = true
                AppLog.w("ProjectionSession: could not write the session properties: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    )

    /** Called from the one state setter of [CommManager]; every transition passes through it. */
    fun onState(state: CommManager.ConnectionState) {
        if (!BuildConfig.CODOS) return
        writer.apply(ProjectionSessionPolicy.isUp(state))
    }

    /** A safety net for service stop: publishes what the state really is, so a stop never leaves a stale answer. */
    fun resync(state: CommManager.ConnectionState) = onState(state)

    /** Process start: drops a value left by an earlier process, and clears on an uncaught exception (best effort). */
    fun start() {
        if (!BuildConfig.CODOS) return
        writer.clearAtStart()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { writer.apply(false) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun setProperty(key: String, value: String) {
        Class.forName("android.os.SystemProperties")
            .getMethod("set", String::class.java, String::class.java)
            .invoke(null, key, value)
    }
}
