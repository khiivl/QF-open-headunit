package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.pm.PackageManager
import com.andrerinas.openheadunit.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Takes the Home and Back buttons off a QF unit's status bar.
 *
 * The firmware has no setting for it, so it is a system overlay of SystemUI (tools/qf-statusbar-overlay) carried in the
 * assets and installed as a Magisk module, which the system reads at boot. From then on the overlay is switched on and
 * off at once: SystemUI builds its status bar again by itself, and must not be restarted for it. Needs root.
 */
object StatusBarButtons {

    private const val PACKAGE = "com.andrerinas.openheadunit.statusbuttons"

    /** The overlay's version in the assets; an older one on the unit is replaced and takes a restart. */
    private const val VERSION = 1

    private const val ASSET = "qf_status_buttons/OhuStatusButtons.apk"
    private const val MODULE = "/data/adb/modules/ohu_status_buttons"

    private const val IS_ON = "cmd overlay list --user 0 | grep -qF '[x] $PACKAGE'"

    enum class Result { HIDDEN, SHOWN, RESTART_TO_FINISH, NO_ROOT, FAILED }

    /** The version of the overlay the system read at this boot, or null when it read none. */
    private fun loadedVersion(context: Context): Int? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PACKAGE, 0).versionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private suspend fun hasRoot(): Boolean {
        if (OemAppManager.rootInUse()) return true
        OemAppManager.requestRootNow()
        return OemAppManager.rootInUse()
    }

    suspend fun hide(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!hasRoot()) return@withContext Result.NO_ROOT
        val loaded = loadedVersion(app)
        // Run every time, to put back a module that was removed or is an older one. The overlay itself is only written
        // when it differs, see installCommand.
        var command = installCommand(app)
        if (loaded == VERSION) command += " && { $IS_ON || cmd overlay enable --user 0 $PACKAGE; }"
        val (code, out) = OemAppManager.executeCommand(app, command)
        AppLog.i("StatusBarButtons: hide, loaded version=$loaded, code=$code ${out.take(200)}")
        when {
            code != 0 -> Result.FAILED
            loaded == VERSION -> Result.HIDDEN
            else -> Result.RESTART_TO_FINISH
        }
    }

    suspend fun show(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!hasRoot()) return@withContext Result.NO_ROOT
        // The module stays, so that hiding them again needs no restart. Never loaded means never hidden.
        if (loadedVersion(app) == null) {
            val (code, _) = OemAppManager.executeCommand(app, "rm -rf $MODULE")
            return@withContext if (code == 0) Result.SHOWN else Result.FAILED
        }
        val (code, out) = OemAppManager.executeCommand(
            app, "if $IS_ON; then cmd overlay disable --user 0 $PACKAGE; fi"
        )
        AppLog.i("StatusBarButtons: show, code=$code ${out.take(200)}")
        if (code == 0) Result.SHOWN else Result.FAILED
    }

    /** Hides them again after the restart that loads the overlay, and after anything that switched it off. */
    fun applyAtStart(context: Context, delayMs: Long) {
        if (BuildConfig.CODOS) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            try {
                if (Settings(app).hideStatusBarButtons) hide(app)
            } catch (e: Exception) {
                AppLog.w("StatusBarButtons: not applied at start: ${e.message}")
            }
        }
    }

    private fun installCommand(context: Context): String {
        val staged = File(context.cacheDir, "OhuStatusButtons.apk")
        context.assets.open(ASSET).use { input -> staged.outputStream().use { input.copyTo(it) } }
        val folder = "$MODULE/system/product/overlay/OhuStatusButtons"
        val overlay = "$folder/OhuStatusButtons.apk"
        // Never written over while it may be loaded, as in AppFadeOverlay.
        val copy = "{ cmp -s ${staged.absolutePath} $overlay || " +
            "{ cp ${staged.absolutePath} $overlay.new && mv -f $overlay.new $overlay; }; }"
        val properties = "id=ohu_status_buttons\\nname=OHU status bar buttons\\nversion=1\\nversionCode=$VERSION\\n" +
            "author=Open HeadUnit\\ndescription=Hides the Home and Back buttons. Installed by Open HeadUnit.\\n"
        return "[ -d /data/adb/magisk ] && mkdir -p $folder && $copy && " +
            "printf '$properties' > $MODULE/module.prop && chmod -R 755 $MODULE && " +
            "chmod 644 $MODULE/module.prop $folder/OhuStatusButtons.apk && rm -f $MODULE/remove $MODULE/disable"
    }
}
