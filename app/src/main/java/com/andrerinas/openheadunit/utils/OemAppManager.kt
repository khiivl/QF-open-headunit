package com.andrerinas.openheadunit.utils

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.adb.AdbManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages detection, status inspection, and disabling/restoring of OEM CarPlay/Android Auto
 * companion apps (such as Zlink, AutoKit, SpeedPlay) that conflict with Open Headunit by stealing
 * USB and Bluetooth endpoints.
 *
 * Execution is performed either via root (libsu) if granted, or via Self-ADB (127.0.0.1:5555).
 * In the CoD OS flavor ([BuildConfig.CODOS]) the app is the system uid and runs the command itself
 * ([SystemUidShell]); neither root nor self-ADB is touched.
 */
object OemAppManager {

    enum class ExecutionBackend {
        ROOT,
        ADB,
        /** CoD OS: this app is `android.uid.system`. */
        SYSTEM,
        NONE
    }

    data class OemAppTarget(
        val id: String,
        val displayName: String,
        val packageCandidates: List<String>,
        val daemonNames: List<String> = emptyList(),
        val daemonScripts: List<String> = emptyList()
    )

    data class OemAppStatus(
        val target: OemAppTarget,
        val installedPackage: String?,
        val isInstalled: Boolean,
        val isEnabled: Boolean,
        val isRunning: Boolean,
        val detectedDaemons: List<String> = emptyList()
    )

    val KNOWN_TARGETS = listOf(
        OemAppTarget(
            id = "zlink",
            displayName = "Zlink / Zlink5",
            packageCandidates = listOf(
                "com.zjinnova.zlink5",
                "com.zjinnova.zlink"
            ),
            daemonNames = listOf("zlink5.sh", "z-link", "zlink"),
            daemonScripts = listOf("/system/bin/zlink5.sh")
        ),
        OemAppTarget(
            id = "autokit",
            displayName = "AutoKit (Carlinkit)",
            packageCandidates = listOf(
                "cn.manstep.phonemirrorBox",
                "cn.manstep.phonemirrorBox.nomap",
                "com.autokit"
            ),
            daemonNames = listOf("phonemirrorBox", "AutoKit"),
            daemonScripts = emptyList()
        ),
        OemAppTarget(
            id = "speedplay",
            displayName = "SpeedPlay / TLink",
            packageCandidates = listOf(
                "com.suding.speedplay",
                "com.tlink.carplay"
            ),
            daemonNames = listOf("speedplay", "tlink"),
            daemonScripts = emptyList()
        ),
        OemAppTarget(
            id = "carlink",
            displayName = "CarLink (FYT)",
            packageCandidates = listOf(
                "com.syu.carlink",
                "com.syu.carlink2"
            ),
            daemonNames = listOf("carlink"),
            daemonScripts = emptyList()
        )
    )

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun isPackageEnabled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }

            val enabledSetting = pm.getApplicationEnabledSetting(packageName)
            when (enabledSetting) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> appInfo.enabled
                else -> appInfo.enabled
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isProcessRunningInActivityManager(context: Context, packageNames: List<String>): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val runningProcesses = am.runningAppProcesses ?: return false
            runningProcesses.any { processInfo ->
                packageNames.any { pkg -> processInfo.processName.contains(pkg) }
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun checkAvailableBackend(): ExecutionBackend = withContext(Dispatchers.IO) {
        if (BuildConfig.CODOS) {
            return@withContext if (SystemUidShell.isSystemUid()) ExecutionBackend.SYSTEM else ExecutionBackend.NONE
        }
        // Root when it is the chosen way and has been granted; local ADB otherwise (not granted, or ADB chosen).
        if (rootInUse()) return@withContext ExecutionBackend.ROOT
        if (adbAvailable()) return@withContext ExecutionBackend.ADB
        ExecutionBackend.NONE
    }

    /** The "Root only" setting: with root granted, ADB is not used at all. Without root, ADB is the fallback. */
    @Volatile var rootOnly = false

    /** The chosen way in (see Settings.shellRootPreferred): root when granted, then ADB; or, when false, ADB only. */
    @Volatile var preferRoot = true

    /**
     * Whether shell work goes through root: root is the chosen way (the setting, or Root only) and has been granted. While
     * this is true ADB is not used anywhere; when root is not granted it is false and ADB is the fallback.
     */
    fun rootInUse(): Boolean {
        if (BuildConfig.CODOS) return false
        return (preferRoot || rootOnly) && rootGranted()
    }

    /** Whether local ADB is listening, on either port the app uses. Never while root is in use. */
    suspend fun adbAvailable(): Boolean =
        !rootInUse() && (AdbManager.isAdbPortOpen() || AdbManager.isAdbPortOpen(9876))

    /** Follows [command]'s output line by line through a root process, until it ends or the coroutine is cancelled. */
    suspend fun streamLinesAsRoot(command: String, onLine: (String) -> Unit): String = withContext(Dispatchers.IO) {
        var process: java.lang.Process? = null
        val closer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { try { process?.destroy() } catch (_: Exception) { } }
        }
        try {
            val proc = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            process = proc
            proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                try { onLine(line) } catch (e: Exception) { AppLog.w("OemAppManager: line handler failed: ${e.message}") }
            }
            "stream closed"
        } catch (e: Exception) {
            "ended: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            closer.cancel()
            try { process?.destroy() } catch (_: Exception) { }
        }
    }

    /** Whether the app holds root, opening the root shell (which raises the su manager's prompt) if that is not known yet. */
    private fun rootGranted(): Boolean {
        if (!LibsuAvailability.usable()) return false
        if (Shell.isAppGrantedRoot() == null) {
            try { Shell.getShell() } catch (_: Throwable) {}
        }
        return Shell.isAppGrantedRoot() == true
    }

    /**
     * Asks for root now, whether or not ADB is there, and says whether it was granted. For the root-only debug switch,
     * which is also the way to grant root on a unit that has ADB.
     */
    suspend fun requestRootNow(): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.CODOS || !LibsuAvailability.usable()) return@withContext false
        // The library keeps the first shell it opened for the life of the process. After a denial that is a plain shell,
        // so without dropping it a root manager entry deleted since would never be asked again until the app restarts.
        try {
            Shell.getCachedShell()?.let { cached -> if (!cached.isRoot) cached.close() }
        } catch (_: Throwable) {}
        try { Shell.getShell() } catch (_: Throwable) {}
        val granted = Shell.isAppGrantedRoot() == true
        AppLog.i("OemAppManager: asked for root on request, granted=$granted")
        granted
    }

    /** Runs [command] as root, or null when root is not granted. Logs sizes only: the output can hold a password. */
    private fun runAsRoot(command: String): Pair<Int, String>? {
        if (!rootGranted()) return null
        return try {
            val result = Shell.cmd(command).exec()
            val out = (result.out + result.err).joinToString("\n").trim()
            AppLog.i(
                "OemAppManager: root ran '${command.take(60)}' code=${result.code} " +
                    "stdout=${result.out.size} lines stderr=${result.err.size} lines, ${out.length} chars"
            )
            Pair(result.code, out)
        } catch (e: Exception) {
            AppLog.e("OemAppManager: Root execution failed: ${e.message}", e)
            null
        }
    }

    @Volatile private var rootAskedThisProcess = false

    /**
     * Asks for root at an app start, when root is the chosen way in (once per process, not on each screen resume): the
     * Magisk (or other su manager) prompt appears then. Opening the shell is both the question and the check, so a grant
     * already given just opens it silently. An answer the user stored as "deny" is the su manager's to keep: it does
     * not prompt again, and only the user changing it there brings the prompt back. Set to ADB, the app never opens a
     * root shell, so nothing is asked.
     */
    suspend fun requestRootIfNotGranted(context: Context) = withContext(Dispatchers.IO) {
        if (BuildConfig.CODOS || rootAskedThisProcess) return@withContext
        if (!preferRoot && !rootOnly) return@withContext
        rootAskedThisProcess = true
        if (!LibsuAvailability.usable()) return@withContext
        // Opening the shell is what raises the prompt, and it waits there for the answer.
        try { Shell.getShell() } catch (_: Throwable) {}
        AppLog.i("OemAppManager: asked for root at start, granted=${Shell.isAppGrantedRoot()}")
    }

    /** Whether a shell can be reached at all, by root or by local ADB. */
    suspend fun hasShellAccess(): Boolean = checkAvailableBackend() != ExecutionBackend.NONE

    suspend fun executeCommand(context: Context, command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (BuildConfig.CODOS) {
            return@withContext SystemUidShell.exec(command)
        }
        // Root when it is the chosen way and has been granted: then ADB is not used, whatever the root command does.
        // Otherwise Self-ADB. Without either the command fails and the full-screen notice explains why.
        if (rootInUse()) {
            return@withContext runAsRoot(command) ?: Pair(-1, "root command failed")
        }

        // Self-ADB (localhost:5555 or localhost:9876)
        if (AdbManager.isAdbPortOpen(5555)) {
            AdbManager.exec(context, command, 5555)
        } else if (AdbManager.isAdbPortOpen(9876)) {
            AdbManager.exec(context, command, 9876)
        } else {
            AdbManager.exec(context, command)
        }
    }

    suspend fun detectApps(context: Context, checkDaemons: Boolean = false): List<OemAppStatus> = withContext(Dispatchers.IO) {
        val statuses = mutableListOf<OemAppStatus>()

        for (target in KNOWN_TARGETS) {
            var installedPkg: String? = null
            for (candidate in target.packageCandidates) {
                if (isPackageInstalled(context, candidate)) {
                    installedPkg = candidate
                    break
                }
            }

            val isInstalled = installedPkg != null
            val isEnabled = installedPkg?.let { isPackageEnabled(context, it) } ?: false
            val isProcRunning = isProcessRunningInActivityManager(context, target.packageCandidates)

            val daemons = mutableListOf<String>()
            if (checkDaemons && (isInstalled || target.daemonNames.isNotEmpty())) {
                for (daemon in target.daemonNames) {
                    val (code, out) = executeCommand(context, "pgrep -f $daemon")
                    if (code == 0 && out.isNotBlank()) {
                        daemons.add(daemon)
                    }
                }
            }

            statuses.add(
                OemAppStatus(
                    target = target,
                    installedPackage = installedPkg,
                    isInstalled = isInstalled,
                    isEnabled = isEnabled,
                    isRunning = isProcRunning || daemons.isNotEmpty(),
                    detectedDaemons = daemons
                )
            )
        }

        statuses
    }

    fun getDisableCommands(context: Context? = null, status: OemAppStatus, codos: Boolean = BuildConfig.CODOS): List<String> {
        if (context != null && status.target.id == "zlink" && Settings(context).projectionMode == Settings.ProjectionMode.CARPLAY_ZLINK) {
            val pkg = status.installedPackage ?: status.target.packageCandidates.first()
            return com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkScreenPolicy.buildHideCommands(pkg)
        }
        val cmds = mutableListOf<String>()
        val pkg = status.installedPackage ?: status.target.packageCandidates.first()
        cmds.add("pm disable-user --user 0 $pkg")
        cmds.add("am force-stop $pkg")
        // CoD OS: signalling another uid's process needs CAP_KILL, which a uid-1000 app does not
        // keep. The package-level force-stop above is what the system uid can do.
        if (!codos) {
            for (daemon in status.target.daemonNames) {
                cmds.add("pkill -f $daemon")
            }
        }
        return cmds
    }

    fun getRestoreCommands(context: Context? = null, status: OemAppStatus, codos: Boolean = BuildConfig.CODOS): List<String> {
        if (context != null && status.target.id == "zlink" && Settings(context).projectionMode == Settings.ProjectionMode.CARPLAY_ZLINK) {
            val pkg = status.installedPackage ?: status.target.packageCandidates.first()
            return com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkScreenPolicy.buildRestoreCommands(pkg)
        }
        val cmds = mutableListOf<String>()
        val pkg = status.installedPackage ?: status.target.packageCandidates.first()
        cmds.add("pm enable --user 0 $pkg")
        // CoD OS: starting a root-owned vendor daemon needs uid 0.
        if (!codos) {
            for (script in status.target.daemonScripts) {
                cmds.add("[ -f $script ] && setsid $script >/dev/null 2>&1 </dev/null &")
            }
        }
        return cmds
    }

    suspend fun disableTarget(context: Context, status: OemAppStatus): Pair<Boolean, String> {
        val cmds = getDisableCommands(context, status)
        val combined = cmds.joinToString(" ; ")
        AppLog.i("OemAppManager: Disabling ${status.target.displayName} with: $combined")
        val (code, out) = executeCommand(context, combined)
        val success = code == 0
        return Pair(success, out)
    }

    suspend fun restoreTarget(context: Context, status: OemAppStatus): Pair<Boolean, String> {
        val cmds = getRestoreCommands(context, status)
        val combined = cmds.joinToString(" ; ")
        AppLog.i("OemAppManager: Restoring ${status.target.displayName} with: $combined")
        val (code, out) = executeCommand(context, combined)
        val success = code == 0
        return Pair(success, out)
    }

    suspend fun runAutoKillIfEnabled(context: Context) = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        if (!settings.autoKillOemApps) return@withContext
        // Disabling zLink would stop CarPlay and the hotspot Android Auto; where it is installed nothing is auto-disabled,
        // whatever an earlier setting or a restored backup says.
        if (com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkScreenPolicy.getInstalledPackages(context).isNotEmpty()) {
            AppLog.i("OemAppManager: autoKillOemApps is on but zLink is installed; not disabling anything.")
            return@withContext
        }

        AppLog.i("OemAppManager: autoKillOemApps is active. Scanning for conflicting apps...")
        val statuses = detectApps(context, checkDaemons = false)
        for (status in statuses) {
            if (status.isInstalled && status.isEnabled) {
                AppLog.i("OemAppManager: Auto-disabling conflicting ${status.target.displayName} (${status.installedPackage})")
                val (success, out) = disableTarget(context, status)
                AppLog.i("OemAppManager: Auto-disable result for ${status.target.displayName}: success=$success, output=$out")
            }
        }
    }
}
