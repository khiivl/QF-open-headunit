package com.andrerinas.openheadunit.utils

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * CoD OS: the replacement for every `su` / libsu / self-ADB route.
 *
 * On CoD OS the app is installed as a system app under `android.uid.system` and there is no root.
 * A command that upstream runs through a root shell runs here through `sh -c` in the app's own
 * process, so it executes as uid 1000. Binder-backed tools (`pm`, `am`, `cmd`, `svc`) work because
 * the system uid holds every signature permission they check. Anything that needs uid 0 or a
 * capability uid 1000 lacks (netfilter, signalling another uid) is not routed here: its caller is
 * disabled in the `codos` flavor. See `cod/ROOT_TO_SYSTEM.md`.
 */
object SystemUidShell {

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 25L

    /** True when this process really is the system uid, which is what the manifest asks for. */
    fun isSystemUid(uid: Int = Process.myUid()): Boolean = uid == Process.SYSTEM_UID

    /**
     * Runs a long-lived [command] (a filtered `logcat`, say) with `sh -c` and hands each output line to [onLine] until the
     * stream ends or the calling coroutine is cancelled, which destroys the process. Returns why it ended.
     */
    suspend fun streamLines(command: String, onLine: (String) -> Unit): String = withContext(Dispatchers.IO) {
        var process: java.lang.Process? = null
        val closer = coroutineContext[Job]?.invokeOnCompletion { try { process?.destroy() } catch (_: Exception) { } }
        try {
            val proc = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            process = proc
            proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                try { onLine(line) } catch (e: Exception) { AppLog.w("SystemUidShell: line handler failed: ${e.message}") }
            }
            "stream closed"
        } catch (e: Exception) {
            "ended: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            closer?.dispose()
            try { process?.destroy() } catch (_: Exception) { }
        }
    }

    /**
     * Runs [command] with `sh -c` and returns its exit code and combined stdout/stderr.
     * A command that does not finish within [timeoutMs] is destroyed and reports -1.
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Pair<Int, String> {
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val proc = process
            val out = ByteArrayOutputStream()
            val reader = Thread {
                try { proc.inputStream.copyTo(out) } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }

            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var code: Int? = null
            while (code == null) {
                code = try { proc.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (code != null) break
                if (SystemClock.elapsedRealtime() >= deadline) break
                Thread.sleep(POLL_MS)
            }
            if (code == null) {
                AppLog.w("SystemUidShell: '${command.take(80)}' did not finish in ${timeoutMs} ms; destroyed")
                proc.destroy()
                return -1 to "timeout"
            }
            reader.join(1_000)
            val text = String(synchronized(out) { out.toByteArray() }, Charsets.UTF_8).trim()
            code to text
        } catch (e: Exception) {
            AppLog.e("SystemUidShell: '${command.take(80)}' failed: ${e.message}", e)
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            try { process?.outputStream?.close() } catch (_: Exception) { }
            try { process?.errorStream?.close() } catch (_: Exception) { }
        }
    }
}
