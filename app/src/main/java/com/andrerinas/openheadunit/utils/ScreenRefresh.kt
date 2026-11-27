package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.PowerManager
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.app.CameraReturn
import com.andrerinas.openheadunit.tools.DisplayPowerTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives a QF K706's own screen at 90 Hz instead of 60.
 *
 * The panel driver takes a replacement description of the panel at run time (see [PanelDescription]), and programs the
 * hardware from it the next time the display comes on, so the display is switched off and straight back on
 * ([DisplayPowerTool]); it stays dark for about three seconds. Nothing is stored in the system: a reboot brings 60 Hz
 * back, so the app switches again after each start while the setting is on.
 *
 * Only the panel it was tried on is touched, and only with its own timings. Needs root.
 */
object ScreenRefresh {

    private const val PANEL = "hx8279_sl101pm32d1720_b06_wuxga"
    private const val NODE = "/sys/class/display/panel0"
    private const val LINK_NODE = "/sys/class/display/dsi/phy_freq"
    private const val LINK_READBACK = "/sys/class/display/dphy0/freq"
    private const val DEVICE_TREE = "/sys/firmware/fdt"

    /** Where the driver's `load_lcddtb` node reads from. */
    private const val LOADER_FILE = "/data/lcd.dtb"

    private const val OWN_PORCHES = "160 136 24"
    private const val OWN_HORIZONTAL = "hfp=160 hbp=136 hsync=24"
    private const val OWN_VERTICAL = "vfp=12 vbp=21 vsync=2"

    /** How long a switch waits for the screen to be free, in tries [RETRY_MS] apart. */
    private const val TRIES = 30
    private const val RETRY_MS = 10_000L

    /** The link speed goes up with the pixel clock, so that every timing on the link keeps its proportion. */
    private data class Rate(val pixelClock: Int, val linkSpeed: Int)

    private val STOCK = Rate(51_200_000, 368_000)   // 59.99 Hz
    private val FAST = Rate(76_800_000, 552_000)    // 89.99 Hz

    private val lock = Mutex()

    enum class Result {
        DONE,
        /** Not this screen. */
        UNSUPPORTED,
        /** The screen is off, or showing the camera: not a moment to darken it. */
        NOT_NOW,
        NO_ROOT,
        FAILED,
    }

    suspend fun set(context: Context, fast: Boolean): Result = withContext(Dispatchers.IO) {
        if (BuildConfig.CODOS) return@withContext Result.UNSUPPORTED
        val app = context.applicationContext
        lock.withLock {
            try {
                switch(app, if (fast) FAST else STOCK)
            } catch (e: Exception) {
                AppLog.w("ScreenRefresh: ${e.message}")
                Result.FAILED
            }
        }
    }

    /** [set] for callers that do not wait. Tries again while the screen is not free. */
    fun apply(context: Context, fast: Boolean) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            repeat(TRIES) {
                if (set(app, fast) != Result.NOT_NOW) return@launch
                delay(RETRY_MS)
            }
        }
    }

    /** Switches again after the unit has started, while the setting is on. */
    fun applyAtStart(context: Context, delayMs: Long) {
        if (BuildConfig.CODOS) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            try {
                val settings = Settings(app)
                if (!settings.screenFastRefresh) return@launch
                // The last switch at a start never finished: the unit went down in the middle of it, perhaps by hand
                // because the screen stayed dark. It is not tried again until the user turns it on.
                if (settings.screenRefreshSwitching) {
                    AppLog.w("ScreenRefresh: the last switch did not finish, turning the setting off")
                    settings.screenFastRefresh = false
                    settings.screenRefreshSwitching = false
                    return@launch
                }
                repeat(TRIES) {
                    settings.screenRefreshSwitching = true
                    val result = set(app, true)
                    settings.screenRefreshSwitching = false
                    if (result != Result.NOT_NOW) return@launch
                    delay(RETRY_MS)
                }
            } catch (e: Exception) {
                AppLog.w("ScreenRefresh: not applied at start: ${e.message}")
            }
        }
    }

    private suspend fun switch(context: Context, rate: Rate): Result {
        if (!hasRoot()) return Result.NO_ROOT
        val now = pixelClock(context) ?: return Result.UNSUPPORTED
        if (now == rate.pixelClock) return Result.DONE
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive || CameraReturn.isCameraShown) return Result.NOT_NOW
        if (load(context, rate) && pixelClock(context) == rate.pixelClock) {
            AppLog.i("ScreenRefresh: the pixel clock is now ${rate.pixelClock}")
            return Result.DONE
        }
        AppLog.w("ScreenRefresh: the screen did not take ${rate.pixelClock}")
        if (rate != STOCK) load(context, STOCK)
        return Result.FAILED
    }

    private suspend fun hasRoot(): Boolean {
        if (OemAppManager.rootInUse()) return true
        OemAppManager.requestRootNow()
        return OemAppManager.rootInUse()
    }

    /** The screen's pixel clock, when it is the panel this knows, with its own porches, at one of the two rates. */
    private suspend fun pixelClock(context: Context): Int? {
        // No cd: the root shell is one that stays open for the whole app.
        val files = listOf("name", "pixel_clock", "hporch", "vporch").joinToString(" ") { "$NODE/$it" }
        val (code, out) = OemAppManager.executeCommand(context, "cat $files")
        val lines = out.lines().map { it.trim() }
        if (code != 0 || lines.size != 4 || lines[0] != PANEL || lines[2] != OWN_HORIZONTAL || lines[3] != OWN_VERTICAL) {
            return null
        }
        return lines[1].toIntOrNull()?.takeIf { it == STOCK.pixelClock || it == FAST.pixelClock }
    }

    /** Hands the driver the panel at [rate] and restarts the display. */
    private suspend fun load(context: Context, rate: Rate): Boolean {
        val tree = File(context.cacheDir, "ohu_device_tree.dtb")
        val description = File(context.cacheDir, "ohu_panel.dtb")
        // Made by the app, so that it stays the app's to read when root fills it.
        tree.writeBytes(ByteArray(0))
        OemAppManager.executeCommand(context, "cat $DEVICE_TREE > ${tree.absolutePath}")
        val blob = PanelDescription.build(
            tree.readBytes(), PANEL, STOCK.pixelClock, STOCK.linkSpeed, rate.pixelClock, rate.linkSpeed
        )
        tree.delete()
        if (blob == null) {
            AppLog.w("ScreenRefresh: the device tree does not describe the panel as expected")
            return false
        }
        description.writeBytes(blob)
        // Writing the porches, unchanged, is what makes the driver take the new mode up. The display is restarted only
        // once the driver reads back what was asked for; if the restart tool cannot run, the unit's own screen off and
        // on does the same, more slowly.
        val command = "cp ${description.absolutePath} $LOADER_FILE && echo 1 > $NODE/load_lcddtb && " +
            "echo ${rate.linkSpeed} > $LINK_NODE && echo '$OWN_PORCHES' > $NODE/hporch && " +
            "[ \"\$(cat $NODE/pixel_clock)\" = ${rate.pixelClock} ] && " +
            "[ \"\$(cat $LINK_READBACK)\" = ${rate.linkSpeed} ]; ohu_ok=\$?; rm -f $LOADER_FILE; " +
            "[ \$ohu_ok = 0 ] && { CLASSPATH='${context.applicationInfo.sourceDir}' app_process /system/bin " +
            "${DisplayPowerTool::class.java.name} || { input keyevent 223; sleep 3; input keyevent 224; }; }"
        val (code, out) = OemAppManager.executeCommand(context, command)
        description.delete()
        if (code != 0) AppLog.w("ScreenRefresh: switching ended with code=$code ${out.take(200)}")
        return code == 0
    }
}
