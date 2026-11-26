package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.Context
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shows a broken-link symbol on CarPlay's car-maker tile, which disconnects CarPlay.
 *
 * Over WiFi the zLink daemon reads that picture from [TARGET] at each connection, writing its own there first. A copy
 * of ours on a read-only memory file system is mounted over that path, so the write is refused and ours is read.
 * Nothing of zLink is changed; a reboot removes the mounts, so this runs at each app start. The tile's name is fixed
 * in the daemon. Needs root, or an ADB that runs as root.
 */
object CarPlayHomeIcon {

    private const val TARGET = "/dev/kalos.png"
    private const val HOLDER = "/dev/ohu_icon"

    fun apply(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val icon = File(app.cacheDir, "carplay_home.png")
                app.resources.openRawResource(R.raw.carplay_tile).use { from ->
                    icon.outputStream().use { from.copyTo(it) }
                }
                val script = File(app.cacheDir, "carplay_home.sh")
                script.writeText(script(icon.absolutePath))
                // In init's mount namespace: a root manager may give an app's shell its own, where the daemon sees nothing.
                val (code, out) = OemAppManager.executeCommand(app, "nsenter -t 1 -m -- sh ${script.absolutePath}")
                AppLog.i("CarPlayHomeIcon: code=$code mounted=${out.trim().takeLast(40)}")
            } catch (e: Exception) {
                AppLog.w("CarPlayHomeIcon: not applied: ${e.message}")
            }
        }
    }

    private fun script(source: String): String = """
        if ! grep -q ' $TARGET ' /proc/mounts; then
          mkdir -p $HOLDER
          grep -q ' $HOLDER ' /proc/mounts || mount -t tmpfs -o size=1m,mode=755 tmpfs $HOLDER
          mount -o remount,rw $HOLDER &&
          cp '$source' $HOLDER/icon.png &&
          chmod 644 $HOLDER/icon.png &&
          mount -o remount,ro $HOLDER &&
          { [ -e $TARGET ] || touch $TARGET; } &&
          mount -o bind $HOLDER/icon.png $TARGET
        fi
        grep -c ' $TARGET ' /proc/mounts
    """.trimIndent() + "\n"
}
