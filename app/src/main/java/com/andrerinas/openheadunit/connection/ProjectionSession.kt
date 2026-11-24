package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.AppLog

/**
 * CoD OS: tells the system whether an Android Auto session is up, in the one place the system already looks.
 *
 * Contract: `cod/OPEN_HEADUNIT_A14_CONTRACT.md` section 10. One variable, `sys.qf.bt.aa.connect` ("true" / "false"): the
 * existing «an Android Auto session is active» flag that QF Bluetooth and the AI bridge read. With Z-Link it is set by QF
 * Bluetooth from Z-Link's own broadcast; with Open Headunit selected Z-Link is off, so OHU sets the same flag itself. It is
 * never set in parallel with Z-Link (the projection switch keeps the two apart). OHU does not broadcast Z-Link's action:
 * QF Bluetooth answers it with an A2DP disconnect and a toast.
 */
object ProjectionSessionPolicy {
    const val PROP_AA_CONNECT = "sys.qf.bt.aa.connect"

    /**
     * Up from the end of the SSL handshake: the phone is a working Android Auto phone and a session exists.
     * Connecting, Connected, StartingTransport, Error and Disconnected are down, so a phone that keeps failing its handshake
     * does not toggle the system.
     */
    fun isUp(state: CommManager.ConnectionState): Boolean =
        state is CommManager.ConnectionState.HandshakeComplete || state is CommManager.ConnectionState.TransportStarted

    fun writes(up: Boolean): List<Pair<String, String>> = listOf(PROP_AA_CONNECT to if (up) "true" else "false")

    /** A process that starts knows of no session: whatever an earlier process left is dropped. */
    fun startWrites(): List<Pair<String, String>> = writes(false)
}

/**
 * Writes the flag through [setter], only when the published answer changes. If a write fails the answer is not
 * remembered, so the next state change tries again. Pure of Android and of the flavor, so it is unit tested.
 */
class ProjectionSessionWriter(
    private val setter: (String, String) -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val lock = Any()
    private var published: Boolean? = null

    fun apply(up: Boolean) = synchronized(lock) {
        if (published == up) return@synchronized
        try {
            ProjectionSessionPolicy.writes(up).forEach { (k, v) -> setter(k, v) }
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

/** The CoD build's instance: the system property write through the hidden `android.os.SystemProperties.set`. */
object ProjectionSessionPublisher {
    @Volatile private var warned = false

    private val writer = ProjectionSessionWriter(
        setter = ::setProperty,
        onFailure = { t ->
            if (!warned) {
                warned = true
                AppLog.w("ProjectionSession: could not write the session flag: ${t.javaClass.simpleName}: ${t.message}")
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
