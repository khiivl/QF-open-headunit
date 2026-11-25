package com.andrerinas.openheadunit.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkHub
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkScreenPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemUidShell
import com.andrerinas.openheadunit.utils.adb.AdbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What a line of the unit's key service log means and where the key should go.
 *
 * The unit's key learning turns each wheel or panel button into a code and hands it to its own voice service, which
 * logs every key it receives. The codes seen on this unit, and how this maps them:
 *  - 304, the voice button: Android Auto's voice assistant (search key), or Siri through the CarPlay daemon (key 1500).
 *  - 87, 88, 85, 89, 90, 126, 127, 5, 6: the media and call keys, passed on as they are. The CarPlay daemon was seen
 *    acting on 85, 87 and 88 and hanging up on 6; it ignored 89, 90, 126, 127 and 5 when sent with no call going.
 *  - 305 and 306, "previous + accept" and "next + reject": the learning's combined keys. While a call is on (the unit's
 *    `sys.qf.call_state`) they are sent as 305 and 306 instead of 88 and 87, and mean accept and reject: sent as 5 and 6.
 */
object BridgeVoiceKeyPolicy {
    /** The code the unit's key learning gives the steering-wheel voice button. */
    const val VOICE_KEY_CODE = 304

    /** The combined "previous + accept" and "next + reject" keys, as the unit sends them during a call. */
    const val PREV_ACCEPT_KEY_CODE = 305
    const val NEXT_REJECT_KEY_CODE = 306

    /** One press arrives as a down and an up within a few milliseconds; holding repeats the down. */
    const val VOICE_DEBOUNCE_MS = 700L
    const val KEY_DEBOUNCE_MS = 250L

    /** The stream starts by repeating its last line, which may be an old press; lines this soon after the start are skipped. */
    const val STARTUP_SKIP_MS = 1_500L

    /**
     * The key zLink's own screen sends its daemon for the voice button: key 1500, down then up. The daemon answers it
     * with "RequestSiri buttondown / buttonup" toward the iPhone. Measured from zLink's own screen on a live session.
     */
    const val CARPLAY_SIRI_KEY = 1500

    /** How long after the down the up follows. */
    const val KEY_PULSE_MS = 80L

    private val RECEIVED = Regex("""onReceived - code: (\d+) - action: (\d+)""")

    /** Media and call keys the unit sends as ordinary Android codes. */
    val PLAIN_KEYS: Set<Int> = setOf(85, 87, 88, 89, 90, 126, 127, 5, 6)

    enum class Target { ANDROID_AUTO, CARPLAY }

    /** A key to send: to which projection, and the key code that projection takes. */
    data class Forward(val target: Target, val key: Int)

    /** The key code and action ("0" down, "1" up) of a line from the unit's key handler, or null for any other line. */
    fun parse(line: String): Pair<Int, Int>? {
        if (!line.contains("McuKeyEventHandler")) return null
        val m = RECEIVED.find(line) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** Wait before re-opening the log stream: 5 s after a stream that ran a while, doubling up to a minute after ones that died at once. */
    const val RETRY_MIN_MS = 5_000L
    const val RETRY_MAX_MS = 60_000L
    const val STREAM_HEALTHY_MS = 30_000L

    fun nextRetryDelayMs(previousMs: Long, streamLivedMs: Long): Long =
        if (streamLivedMs >= STREAM_HEALTHY_MS) RETRY_MIN_MS else minOf(previousMs * 2, RETRY_MAX_MS)

    fun debounceMs(code: Int): Long = if (code == VOICE_KEY_CODE) VOICE_DEBOUNCE_MS else KEY_DEBOUNCE_MS

    /** The key code Android Auto takes for [code], or null if the unit's key is not one it forwards. */
    fun androidAutoKey(code: Int): Int? = when (code) {
        VOICE_KEY_CODE -> KeyEvent.KEYCODE_SEARCH
        PREV_ACCEPT_KEY_CODE -> KeyEvent.KEYCODE_CALL
        NEXT_REJECT_KEY_CODE -> KeyEvent.KEYCODE_ENDCALL
        in PLAIN_KEYS -> code
        else -> null
    }

    /** The key code the CarPlay daemon takes for [code], or null if the unit's key is not one it forwards. */
    fun carPlayKey(code: Int): Int? = when (code) {
        VOICE_KEY_CODE -> CARPLAY_SIRI_KEY
        PREV_ACCEPT_KEY_CODE -> KeyEvent.KEYCODE_CALL
        NEXT_REJECT_KEY_CODE -> KeyEvent.KEYCODE_ENDCALL
        in PLAIN_KEYS -> code
        else -> null
    }

    /**
     * What to do with a key line: where it goes and as which code, or null for nothing. Only the key down counts, only
     * with the setting on, and only once a session is up: Android Auto's if it is connected, else CarPlay's.
     */
    fun forward(
        code: Int, action: Int, nowMs: Long, lastForwardedMs: Long, sinceStreamStartMs: Long,
        enabled: Boolean, androidAutoConnected: Boolean, carPlayActive: Boolean
    ): Forward? {
        if (!enabled || action != 0 || sinceStreamStartMs < STARTUP_SKIP_MS) return null
        if (lastForwardedMs != 0L && nowMs - lastForwardedMs < debounceMs(code)) return null
        return when {
            androidAutoConnected -> androidAutoKey(code)?.let { Forward(Target.ANDROID_AUTO, it) }
            carPlayActive -> carPlayKey(code)?.let { Forward(Target.CARPLAY, it) }
            else -> null
        }
    }
}

/**
 * Forwards the unit's steering-wheel and panel buttons to the running projection, Android Auto or CarPlay.
 *
 * The buttons never reach apps as normal keys: the unit's key learning hands them to its own voice service
 * (`com.qf.ai.bridge`), which looks for an assistant app of its own and drops them when there is none. That service logs
 * every key it receives, so this follows its log over the local adb connection and sends the matching key on (see
 * [BridgeVoiceKeyPolicy]). It reads one filtered log tag and nothing else. It only runs on a zLink (QF) unit with the
 * setting on, and re-connects by itself if the stream ends.
 */
object BridgeVoiceKeyWatcher {
    private const val LOGCAT_COMMAND = "logcat -T 1 -v brief -s McuKeyEventHandler:D"
    private var job: Job? = null

    /** Starts or stops the watcher to match the setting; safe to call whenever the setting or the app state may have changed. */
    @Synchronized
    fun sync(context: Context) {
        val app = context.applicationContext
        val wanted = Settings(app).voiceKeyFromBridge && ZlinkScreenPolicy.getInstalledPackages(app).isNotEmpty()
        if (!wanted) { stop(); return }
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { run(app) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    // Shared by every loop, so a loop that has not quite ended cannot send a key a second time.
    private val lastForwardedMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * True while the key log is being followed. Then this sends the unit's next and previous keys itself (a short press on
     * release, a hold as a seek), so the projection screen leaves those two keys alone; otherwise it forwards them as before.
     */
    @Volatile
    private var following = false

    /** Whether the watcher is the one sending next and previous to Android Auto right now. */
    fun handlesSeekKeys(context: Context): Boolean = following && Settings(context.applicationContext).voiceKeyFromBridge

    private val seekHold = MediaSeekHold()
    private val seekHandler = Handler(Looper.getMainLooper())
    private var seekApp: Context? = null

    /** Ends a hold whose key up never came, so the phone cannot be left seeking. */
    private val seekWatchdog = Runnable {
        // The handler is on the main thread; sending a key can touch the network, so the work moves off it.
        CoroutineScope(Dispatchers.IO).launch {
            val steps = synchronized(seekHold) { seekHold.expire(android.os.SystemClock.elapsedRealtime()) }
            steps.forEach { sendSeekStep(it) }
            scheduleSeekWatchdog()
        }
    }

    private fun scheduleSeekWatchdog() {
        seekHandler.removeCallbacks(seekWatchdog)
        if (synchronized(seekHold) { seekHold.isHolding() }) {
            seekHandler.postDelayed(seekWatchdog, MediaSeekHold.WATCHDOG_MS / 2)
        }
    }

    private fun handleSeekKey(app: Context, code: Int, action: Int, now: Long) {
        seekApp = app
        val step = synchronized(seekHold) { seekHold.onKey(code, action, now) }
        if (step != null) sendSeekStep(step)
        scheduleSeekWatchdog()
    }

    private fun sendSeekStep(step: MediaSeekHold.Step) {
        val app = seekApp ?: return
        val commManager = App.provide(app).commManager
        when (step.kind) {
            MediaSeekHold.Kind.PRESS -> {
                AppLog.i("BridgeVoiceKey: unit key ${step.key} (short press) goes to ANDROID_AUTO as key ${step.key}.")
                commManager.sendKey(step.key, true, null, "bridge-key")
                commManager.sendKey(step.key, false, null, "bridge-key")
            }
            MediaSeekHold.Kind.SEEK_DOWN, MediaSeekHold.Kind.SEEK_REPEAT -> {
                AppLog.i("BridgeVoiceKey: unit key held, Android Auto gets key ${step.key} down as a long press (${step.kind}).")
                // Its own path: a second down of a key already down would be dropped as a repeat by the key debounce.
                commManager.send(step.key, true, longPress = true)
            }
            MediaSeekHold.Kind.SEEK_UP -> {
                AppLog.i("BridgeVoiceKey: unit key released, Android Auto gets key ${step.key} up.")
                commManager.send(step.key, false)
            }
        }
    }

    private suspend fun run(app: Context) {
        var retryDelayMs = BridgeVoiceKeyPolicy.RETRY_MIN_MS / 2
        while (currentCoroutineContext().isActive) {
            // CoD OS: no self-ADB. The app is the system user there and reads the one log tag itself.
            // Root granted and chosen: the log is followed through root and ADB is not touched. Otherwise ADB.
            val viaRoot = !BuildConfig.CODOS &&
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.andrerinas.openheadunit.utils.OemAppManager.rootInUse()
                }
            val port = if (BuildConfig.CODOS || viaRoot) 0 else when {
                AdbManager.isAdbPortOpen(5555) -> 5555
                AdbManager.isAdbPortOpen(9876) -> 9876
                else -> { delay(30_000L); continue }
            }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            AppLog.i(
                if (BuildConfig.CODOS) "BridgeVoiceKey: following the unit's key service log as the system user."
                else if (viaRoot) "BridgeVoiceKey: following the unit's key service log through root."
                else "BridgeVoiceKey: following the unit's key service log over adb port $port."
            )
            val onLine: (String) -> Unit = onLine@{ line ->
                val (code, action) = BridgeVoiceKeyPolicy.parse(line) ?: return@onLine
                val now = android.os.SystemClock.elapsedRealtime()
                val connected = App.provide(app).commManager.isConnected
                val carPlay = ZlinkHub.hasActiveSession
                // Next and previous in Android Auto go on with their real press length, so the phone sees a long press.
                if (connected && MediaSeekHold.isSeekKey(code) && Settings(app).voiceKeyFromBridge &&
                    now - startedAt >= BridgeVoiceKeyPolicy.STARTUP_SKIP_MS
                ) {
                    handleSeekKey(app, code, action, now)
                    return@onLine
                }
                // Decide and claim the key in one step, so two loops that both see the line cannot both send it.
                val forward = synchronized(lastForwardedMs) {
                    BridgeVoiceKeyPolicy.forward(
                        code, action, now, lastForwardedMs[code] ?: 0L, now - startedAt,
                        Settings(app).voiceKeyFromBridge, connected, carPlay
                    )?.also { lastForwardedMs[code] = now }
                }
                if (forward == null) {
                    if (action == 0) {
                        AppLog.i("BridgeVoiceKey: unit key $code seen, not forwarded (Android Auto connected=$connected, CarPlay active=$carPlay).")
                    }
                    return@onLine
                }
                AppLog.i("BridgeVoiceKey: unit key $code goes to ${forward.target} as key ${forward.key}.")
                when (forward.target) {
                    BridgeVoiceKeyPolicy.Target.ANDROID_AUTO -> {
                        val commManager = App.provide(app).commManager
                        commManager.sendKey(forward.key, true, null, "bridge-key")
                        commManager.sendKey(forward.key, false, null, "bridge-key")
                    }
                    BridgeVoiceKeyPolicy.Target.CARPLAY -> {
                        ZlinkHub.injectKey(forward.key, true)
                        Handler(Looper.getMainLooper()).postDelayed(
                            { ZlinkHub.injectKey(forward.key, false) },
                            BridgeVoiceKeyPolicy.KEY_PULSE_MS
                        )
                    }
                }
            }
            following = true
            val why = try {
                if (BuildConfig.CODOS) SystemUidShell.streamLines(LOGCAT_COMMAND, onLine)
                else if (viaRoot) com.andrerinas.openheadunit.utils.OemAppManager.streamLinesAsRoot(LOGCAT_COMMAND, onLine)
                else AdbManager.streamLines(app, LOGCAT_COMMAND, port, onLine)
            } finally {
                following = false
            }
            // A stream that dies at once (no permission to read the log, say) is retried ever more slowly, not every 5 s.
            val lived = android.os.SystemClock.elapsedRealtime() - startedAt
            retryDelayMs = BridgeVoiceKeyPolicy.nextRetryDelayMs(retryDelayMs, lived)
            AppLog.i("BridgeVoiceKey: the log stream $why; reconnecting in ${retryDelayMs / 1000} s.")
            delay(retryDelayMs)
        }
    }
}
