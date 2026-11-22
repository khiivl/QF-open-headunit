package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import kotlinx.coroutines.Dispatchers
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
     * Idempotently installs the redirect rules using available root or self-ADB backend.
     * Safe to call from any thread; dispatches to [Dispatchers.IO].
     */
    suspend fun ensureRedirectRules(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val backend = OemAppManager.checkAvailableBackend()
        AppLog.i("zlink redirect: ensuring iptables rules using backend $backend")
        val cmd = buildEnsureCommand()
        val (code, out) = OemAppManager.executeCommand(context, cmd)
        val success = code == 0
        AppLog.i("zlink redirect: ensure completed with code=$code backend=$backend out=$out")
        Pair(success, out)
    }

    /**
     * Removes the redirect rules using available root or self-ADB backend.
     * Safe to call from any thread; dispatches to [Dispatchers.IO].
     */
    suspend fun removeRedirectRules(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val backend = OemAppManager.checkAvailableBackend()
        AppLog.i("zlink redirect: removing iptables rules using backend $backend")
        val cmd = buildRemoveCommand()
        val (code, out) = OemAppManager.executeCommand(context, cmd)
        val success = code == 0
        AppLog.i("zlink redirect: remove completed with code=$code backend=$backend out=$out")
        Pair(success, out)
    }
}
