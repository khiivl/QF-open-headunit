package com.andrerinas.openheadunit.connection

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
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

    /** The projection switch's setting (design `PROJECTION_SWITCH_DESIGN.md` section 13): `openheadunit` or `zlink`; absent means OHU. */
    const val SELECTION_KEY = "cod_projection_app"
    const val SELECTION_ZLINK = "zlink"
    const val ZLINK_PACKAGE = "com.zjinnova.zlink"

    /**
     * THE GUARD. Z-Link's own receiver of this action must never see OHU's CONNECTED as its own, so OHU announces a session
     * only while Z-Link is **not** the active projection app: the person has not selected Z-Link, and the Z-Link package is
     * absent or disabled (what the switch does to it while OHU is selected). With Z-Link enabled, Z-Link's own broadcasts go
     * out and OHU stays silent, whatever else is true.
     *
     * @param selection the stored setting, or null when it was never written
     * @param zlinkEnabledState `PackageManager.getApplicationEnabledSetting` of Z-Link, or null when the package is not installed
     */
    fun mayAnnounce(selection: String?, zlinkEnabledState: Int?): Boolean {
        if (selection == SELECTION_ZLINK) return false
        return when (zlinkEnabledState) {
            null,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> false // DEFAULT (enabled by its manifest) or ENABLED: Z-Link is the live projection app
        }
    }

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
    private val onFailure: (Throwable) -> Unit = {},
    /** The guard (see [ProjectionSessionPolicy.mayAnnounce]): a connect is sent only while this says OHU is the active projection app. */
    private val mayAnnounce: () -> Boolean = { true }
) {
    private val lock = Any()
    private var published: Boolean? = null
    private var mode: String = ProjectionSessionPolicy.MODE_WIRELESS

    /** [type] is needed for a connect; a disconnect uses the mode it announced. */
    fun apply(up: Boolean, type: ProjectionConnection.Type? = null) = synchronized(lock) {
        if (published == up) return@synchronized
        // Guarded: a connect while Z-Link is the active projection app is never sent. A disconnect is sent only for a connect that went out.
        if (up && !mayAnnounce()) return@synchronized
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
        if (!systemThinksUp || !mayAnnounce()) return@synchronized
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
        },
        mayAnnounce = { guard() }
    )

    /** Reads the two facts of [ProjectionSessionPolicy.mayAnnounce] from the system; a failed read counts as «not announcing». */
    private fun guard(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val selection = AndroidSettings.Global.getString(ctx.contentResolver, ProjectionSessionPolicy.SELECTION_KEY)
            val zlink = try {
                ctx.packageManager.getApplicationEnabledSetting(ProjectionSessionPolicy.ZLINK_PACKAGE)
            } catch (_: IllegalArgumentException) {
                null // not installed
            }
            ProjectionSessionPolicy.mayAnnounce(selection, zlink)
        } catch (t: Throwable) {
            false
        }
    }

    /** Called from the one state setter of [CommManager]; every transition passes through it. */
    fun onState(context: Context?, state: CommManager.ConnectionState, type: ProjectionConnection.Type?) {
        if (!BuildConfig.CODOS || context == null) return
        appContext = context.applicationContext
        writer.apply(ProjectionSessionPolicy.isUp(state), type)
    }

    /** A safety net for service stop: publishes what the state really is, so a stop never leaves a stale answer. */
    fun resync(context: Context?, state: CommManager.ConnectionState) = onState(context, state, null)

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
