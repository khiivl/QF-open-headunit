package com.andrerinas.openheadunit.main

import android.content.Context
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stops another app's process from the launcher's app lists: the full drawer and the favorites row
 * of the home screen. It runs `am force-stop` through the root shell or the self-ADB connection that
 * the OEM app tools already use, so it needs one of them.
 */
object AppKiller {

    private const val TAG = "AppKiller"

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Package names come from the package manager; this keeps anything else from reaching a shell. */
    internal fun isSafePackage(packageName: String): Boolean = PACKAGE_NAME.matches(packageName)

    /** Stops [packageName] in the background and reports the result in a toast. */
    fun kill(context: Context, packageName: String, label: String) {
        if (!isSafePackage(packageName)) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val (code, out) = try {
                OemAppManager.executeCommand(appContext, "am force-stop $packageName")
            } catch (e: Exception) {
                AppLog.w(TAG, "Kill of $packageName failed", e)
                -1 to (e.message ?: "")
            }
            AppLog.i(TAG, "Kill $packageName: exit $code ${out.take(200)}")
            withContext(Dispatchers.Main) {
                ToastUtils.showToast(
                    appContext,
                    appContext.getString(
                        if (code == 0) R.string.drawer_kill_app_done else R.string.drawer_kill_app_failed,
                        label
                    ),
                    Toast.LENGTH_SHORT,
                    force = true
                )
            }
        }
    }
}
