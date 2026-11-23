package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages the visibility of the OEM zLink app's UI screens.
 *
 * For CarPlay feed integration, the zLink daemon and background services must stay alive to maintain
 * the Bluetooth/Wi-Fi connection and licensing peer, while its UI activities are hidden via Package
 * Manager (`pm disable`) so they never steal screen focus or contend for the single hardware video decoder.
 *
 * ActivationActivity and CheckPermissionsAndActivationActivity are intentionally left alone so
 * zLink's license verification is never touched.
 */
object ZlinkScreenPolicy {

    val TARGET_PACKAGES = listOf(
        "com.zjinnova.zlink",
        "com.zjinnova.zlink5"
    )

    val UI_ACTIVITIES = listOf(
        "com.zjinnova.android.zlink.features.main.MainActivity",
        "com.zjinnova.android.zlink.features.launcher.CarPlayActivity",
        "com.zjinnova.android.zlink.features.launcher.CarPlayAutoActivity",
        "com.zjinnova.android.zlink.features.launcher.CarPlayUnavailableActivity",
        "com.zjinnova.android.zlink.features.launcher.AutoActivity",
        "com.zjinnova.android.zlink.features.launcher.AutoUnavailableActivity",
        "com.zjinnova.android.zlink.features.launcher.HiCarActivity",
        "com.zjinnova.android.zlink.features.launcher.HiCarUnavailableActivity",
        "com.zjinnova.android.zlink.features.launcher.CarLifeActivity",
        "com.zjinnova.android.zlink.features.launcher.MirrorActivity",
        "com.zjinnova.android.zlink.features.launcher.DlnaActivity",
        "com.zjinnova.android.zlink.features.dlna.DlnaActivity"
    )

    fun buildHideCommands(pkg: String, activities: List<String> = UI_ACTIVITIES): List<String> =
        activities.map { "pm disable $pkg/$it" }

    fun buildRestoreCommands(pkg: String, activities: List<String> = UI_ACTIVITIES): List<String> =
        activities.map { "pm default-state $pkg/$it" }

    fun buildHideCommand(pkg: String, activities: List<String> = UI_ACTIVITIES): String =
        buildHideCommands(pkg, activities).joinToString(" ; ")

    fun buildRestoreCommand(pkg: String, activities: List<String> = UI_ACTIVITIES): String =
        buildRestoreCommands(pkg, activities).joinToString(" ; ")

    /**
     * Determines whether zLink's UI is currently hidden by checking the enabled state of MainActivity.
     * Returns true if disabled (hidden), false if enabled (visible), or null if package not installed.
     */
    fun isUiHidden(context: Context): Boolean? {
        val pm = context.packageManager
        for (pkg in TARGET_PACKAGES) {
            if (OemAppManager.isPackageInstalled(context, pkg)) {
                return try {
                    val cn = ComponentName(pkg, "com.zjinnova.android.zlink.features.main.MainActivity")
                    val state = pm.getComponentEnabledSetting(cn)
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                } catch (_: Exception) {
                    false
                }
            }
        }
        return null
    }

    /**
     * Finds installed zLink packages on the unit.
     */
    fun getInstalledPackages(context: Context): List<String> =
        TARGET_PACKAGES.filter { OemAppManager.isPackageInstalled(context, it) }

    /**
     * Applies hide or restore state using available root or self-ADB execution backend.
     * Safe to call from any thread; dispatches to [Dispatchers.IO].
     */
    suspend fun applyVisibility(context: Context, hide: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val packages = getInstalledPackages(context).ifEmpty { listOf("com.zjinnova.zlink") }
        val backend = OemAppManager.checkAvailableBackend()
        AppLog.i("zlink screen: applyVisibility hide=$hide for $packages using backend $backend")

        val cmds = mutableListOf<String>()
        for (pkg in packages) {
            if (hide) {
                cmds.addAll(buildHideCommands(pkg))
            } else {
                cmds.addAll(buildRestoreCommands(pkg))
            }
        }

        val combinedCmd = cmds.joinToString(" ; ")
        val (code, out) = OemAppManager.executeCommand(context, combinedCmd)
        val success = code == 0
        AppLog.i("zlink screen: applyVisibility completed success=$success code=$code out=$out")
        Pair(success, out)
    }
}
