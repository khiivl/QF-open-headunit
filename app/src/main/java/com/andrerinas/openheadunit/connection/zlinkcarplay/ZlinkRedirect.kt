package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.Context
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages iptables REDIRECT rules so that loopback TCP connections from the root zLink daemon (uid 0)
 * on ports 1666, 1777, 1888, 1999 are redirected to OHU's in-process [ZlinkProxy] on 11666, 11777,
 * 11888, 11999.
 */
object ZlinkRedirect {
    val PORTS = listOf(1666, 1777, 1888, 1999)
    const val PORT_OFFSET = 10000

    fun ruleCheck(port: Int): String {
        val target = port + PORT_OFFSET
        return "iptables -t nat -C OUTPUT -p tcp -d 127.0.0.1 --dport $port -m owner --uid-owner 0 -j REDIRECT --to-ports $target"
    }

    fun ruleAdd(port: Int): String {
        val target = port + PORT_OFFSET
        return "iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport $port -m owner --uid-owner 0 -j REDIRECT --to-ports $target"
    }

    fun ruleDelete(port: Int): String {
        val target = port + PORT_OFFSET
        return "iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport $port -m owner --uid-owner 0 -j REDIRECT --to-ports $target"
    }

    /**
     * Pure command builder: creates an idempotent shell command that checks for each rule
     * and appends it only if not already present.
     */
    fun buildEnsureCommand(ports: List<Int> = PORTS): String {
        return ports.joinToString(" ; ") { port ->
            "${ruleCheck(port)} 2>/dev/null || ${ruleAdd(port)}"
        }
    }

    /**
     * Pure command builder: creates an idempotent shell command that checks for each rule
     * and deletes it if present.
     */
    fun buildRemoveCommand(ports: List<Int> = PORTS): String {
        return ports.joinToString(" ; ") { port ->
            "${ruleCheck(port)} 2>/dev/null && ${ruleDelete(port)} || true"
        }
    }

    /**
     * CoD OS: netfilter needs CAP_NET_ADMIN, which a uid-1000 app does not have, and the rules only
     * match traffic of a root Z-Link daemon. Nothing to redirect, nothing to run.
     */
    private fun codosDisabled(what: String): Pair<Boolean, String> {
        AppLog.w("zlink redirect: $what skipped; iptables needs uid 0 and is disabled in CoD OS")
        return false to "disabled in CoD OS"
    }

    /**
     * Idempotently installs the redirect rules using available root or self-ADB backend.
     * Safe to call from any thread; dispatches to [Dispatchers.IO].
     */
    suspend fun ensureRedirectRules(context: Context): Pair<Boolean, String> = ensureMutex.withLock {
        if (BuildConfig.CODOS) return@withLock codosDisabled("ensure")
        val now = android.os.SystemClock.elapsedRealtime()
        if (!shouldEnsure(now, lastEnsureOkMs)) return@withLock Pair(true, "recently ensured")
        withContext(Dispatchers.IO) {
            val backend = OemAppManager.checkAvailableBackend()
            AppLog.i("zlink redirect: ensuring iptables rules using backend $backend")
            val cmd = buildEnsureCommand()
            val (code, out) = OemAppManager.executeCommand(context, cmd)
            val success = code == 0
            if (success) { lastEnsureOkMs = android.os.SystemClock.elapsedRealtime(); lastRemoveOkMs = 0L }
            AppLog.i("zlink redirect: ensure completed with code=$code backend=$backend out=$out")
            Pair(success, out)
        }
    }

    // The service is started from several places at once (app start, mode switch), and each start asks for the rules.
    // One check at a time, and none at all right after a successful one.
    private val ensureMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastEnsureOkMs = 0L
    private const val ENSURE_COALESCE_MS = 30_000L

    /** Whether the rules need checking again: not within [ENSURE_COALESCE_MS] of the last success. */
    fun shouldEnsure(nowMs: Long, lastOkMs: Long): Boolean = lastOkMs == 0L || nowMs - lastOkMs >= ENSURE_COALESCE_MS

    /**
     * Removes the redirect rules using available root or self-ADB backend.
     * Safe to call from any thread; dispatches to [Dispatchers.IO].
     */
    suspend fun removeRedirectRules(context: Context): Pair<Boolean, String> = ensureMutex.withLock {
        if (BuildConfig.CODOS) return@withLock codosDisabled("remove")
        val now = android.os.SystemClock.elapsedRealtime()
        if (!shouldEnsure(now, lastRemoveOkMs)) return@withLock Pair(true, "recently removed")
        lastEnsureOkMs = 0L
        withContext(Dispatchers.IO) {
            val backend = OemAppManager.checkAvailableBackend()
            AppLog.i("zlink redirect: removing iptables rules using backend $backend")
            val cmd = buildRemoveCommand()
            val (code, out) = OemAppManager.executeCommand(context, cmd)
            val success = code == 0
            if (success) lastRemoveOkMs = android.os.SystemClock.elapsedRealtime()
            AppLog.i("zlink redirect: remove completed with code=$code backend=$backend out=$out")
            Pair(success, out)
        }
    }

    @Volatile private var lastRemoveOkMs = 0L
}
