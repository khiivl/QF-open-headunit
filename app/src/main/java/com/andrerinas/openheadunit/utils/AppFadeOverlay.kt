package com.andrerinas.openheadunit.utils

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fades between apps on the whole unit.
 *
 * The fades are a system overlay (tools/qf-fade-overlay) carried in the assets. The system takes an overlay of its own
 * resources only from a system partition, so it is put there as a Magisk module. This Android 10 reads the animations of
 * the system's own process at boot and not again, so the overlay is a static one and a change takes a restart, on as well
 * as off. The QF firmware runs its transitions at a tenth of their length, too short to see, so the fade also sets that
 * scale to normal and puts the old one back when it is turned off. Needs root.
 */
object AppFadeOverlay {

    const val PACKAGE = "com.andrerinas.openheadunit.appfade"

    /** The overlay's version in the assets; an older one on the unit is replaced and takes a restart. */
    private const val VERSION = 3

    private const val ASSET = "qf_app_fade/OhuAppFade.apk"
    private const val MODULE = "/data/adb/modules/ohu_app_fade"
    private const val SCALE = "transition_animation_scale"

    enum class Result { ON, RESTART_TO_FINISH_ON, OFF, RESTART_TO_FINISH_OFF, NO_ROOT, FAILED }

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

    suspend fun enable(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!hasRoot()) return@withContext Result.NO_ROOT
        val settings = Settings(app)
        if (settings.appFadePreviousScale.isEmpty()) {
            val (code, out) = OemAppManager.executeCommand(app, "settings get global $SCALE")
            if (code == 0) settings.appFadePreviousScale = out.trim()
        }
        val loaded = loadedVersion(app)
        // Run every time, to put back a module that was removed or is an older one. The overlay itself is only written
        // when it differs, see installCommand.
        val (code, out) = OemAppManager.executeCommand(app, installCommand(app) + " && settings put global $SCALE 1.0")
        AppLog.i("AppFadeOverlay: enable, loaded version=$loaded, code=$code ${out.take(200)}")
        when {
            code != 0 -> Result.FAILED
            loaded == VERSION -> Result.ON
            else -> Result.RESTART_TO_FINISH_ON
        }
    }

    suspend fun disable(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!hasRoot()) return@withContext Result.NO_ROOT
        val settings = Settings(app)
        val previous = settings.appFadePreviousScale.toFloatOrNull()
        val restore = if (previous != null) "settings put global $SCALE $previous" else "settings delete global $SCALE"
        val loaded = loadedVersion(app)
        val (code, out) = OemAppManager.executeCommand(app, "rm -rf $MODULE && $restore")
        AppLog.i("AppFadeOverlay: disable, loaded version=$loaded, code=$code ${out.take(200)}")
        if (code != 0) return@withContext Result.FAILED
        settings.appFadePreviousScale = ""
        if (loaded == null) Result.OFF else Result.RESTART_TO_FINISH_OFF
    }

    /** Options that fade in whatever this app opens from its own screens, or null with the fade off. */
    fun launchOptions(context: Context): Bundle? =
        if (!Settings(context).appFade) null
        else ActivityOptions.makeCustomAnimation(context, R.anim.ohu_fade_in, R.anim.ohu_hold).toBundle()

    /**
     * Fades [activity], the home screen, in as it comes forward. The system takes the animation of a switch from the app
     * being left, and the QF firmware's dark themes name none, so most apps would just vanish. Call it from onResume; it
     * does nothing when no switch is under way.
     */
    @Suppress("DEPRECATION")
    fun fadeHome(activity: Activity) {
        // The home screen is drawn under the app it replaces, so that one has to fade out for the fade to be seen.
        if (Settings(activity).appFade) activity.overridePendingTransition(R.anim.ohu_fade_in, R.anim.ohu_fade_out)
    }

    /** Keeps the module and the transition speed in place while the setting is on. */
    fun applyAtStart(context: Context, delayMs: Long) {
        if (BuildConfig.CODOS) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            try {
                if (Settings(app).appFade) enable(app)
            } catch (e: Exception) {
                AppLog.w("AppFadeOverlay: not applied at start: ${e.message}")
            }
        }
    }

    private fun installCommand(context: Context): String {
        val staged = File(context.cacheDir, "OhuAppFade.apk")
        context.assets.open(ASSET).use { input -> staged.outputStream().use { input.copyTo(it) } }
        val folder = "$MODULE/system/product/overlay/OhuAppFade"
        val overlay = "$folder/OhuAppFade.apk"
        // Never written over while it may be loaded: rewriting it, even with the same bytes, left running processes
        // unable to follow the unit from day to night. A new version goes beside it and is moved into its place.
        val copy = "{ cmp -s ${staged.absolutePath} $overlay || " +
            "{ cp ${staged.absolutePath} $overlay.new && mv -f $overlay.new $overlay; }; }"
        val properties = "id=ohu_app_fade\\nname=OHU app fade\\nversion=1\\nversionCode=$VERSION\\nauthor=Open HeadUnit\\n" +
            "description=Fades between apps. Installed by Open HeadUnit.\\n"
        return "[ -d /data/adb/magisk ] && mkdir -p $folder && $copy && " +
            "printf '$properties' > $MODULE/module.prop && chmod -R 755 $MODULE && " +
            "chmod 644 $MODULE/module.prop $folder/OhuAppFade.apk && rm -f $MODULE/remove $MODULE/disable"
    }
}
