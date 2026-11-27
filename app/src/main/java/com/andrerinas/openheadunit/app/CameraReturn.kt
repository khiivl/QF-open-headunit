package com.andrerinas.openheadunit.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkCarPlayActivity
import com.andrerinas.openheadunit.connection.zlinkcarplay.ZlinkHub
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Puts the projection back after the QF unit's rear or front camera view.
 *
 * The view is a window of the unit's camera app drawn over whatever is in front, and it announces itself with
 * [ACTION_CAMERA_VIEW]. Around it the unit can send Home, which leaves the driver on the main screen. So the projection
 * screen that was under the view is noted when it opens, and brought back if it is not in front after it closes.
 *
 * Everything here runs on the main thread: the activity callbacks, the receiver and the checks.
 */
object CameraReturn {

    private const val ACTION_CAMERA_VIEW = "com.qf.action.BACKCAR_VIEW"
    private const val EXTRA_SHOW = "show"

    private val handler = Handler(Looper.getMainLooper())

    private var front: Class<out Activity>? = null
    private var last: Class<out Activity>? = null
    private var lastLeftAt = 0L

    /** The screen to bring back, from the camera view opening until the last check after it closed. */
    private var wanted: Class<out Activity>? = null

    fun register(context: Context) {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_CAMERA_VIEW) return
                onCameraView(app, intent.getBooleanExtra(EXTRA_SHOW, false))
            }
        }
        // Exported: the camera app is another package. All a stranger's copy could do is show the projection again.
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_CAMERA_VIEW), ContextCompat.RECEIVER_EXPORTED)
    }

    fun onActivityResumed(activity: Activity) {
        if (activity is AapProjectionActivity || activity is ZlinkCarPlayActivity) front = activity.javaClass
    }

    fun onActivityPaused(activity: Activity) {
        if (front != activity.javaClass) return
        last = front
        lastLeftAt = SystemClock.elapsedRealtime()
        front = null
    }

    private fun onCameraView(app: Context, shown: Boolean) {
        handler.removeCallbacksAndMessages(null)
        if (shown) {
            // A view that comes back before the checks ran out keeps the screen noted the first time.
            wanted = wanted
                ?: CameraReturnPolicy.screenUnderCamera(front, last, lastLeftAt, SystemClock.elapsedRealtime())
            AppLog.i("CameraReturn: the camera view opened over ${wanted?.simpleName ?: "no projection"}")
            return
        }
        val screen = wanted ?: return
        AppLog.i("CameraReturn: the camera view closed, ${screen.simpleName} is to be in front")
        CameraReturnPolicy.CHECKS_MS.forEach { delayMs -> handler.postDelayed({ bringBack(app, screen) }, delayMs) }
        handler.postDelayed({ wanted = null }, CameraReturnPolicy.CHECKS_MS.last())
    }

    private fun bringBack(app: Context, screen: Class<out Activity>) {
        if (front == screen) return
        val androidAuto = screen == AapProjectionActivity::class.java
        val live = if (androidAuto) App.provide(app).commManager.isConnected else ZlinkHub.hasActiveSession
        if (!live) return
        val intent = if (androidAuto) {
            AapProjectionActivity.intent(app)
                .putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(app, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLog.i("CameraReturn: bringing ${screen.simpleName} back")
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            AppLog.w("CameraReturn: could not bring it back: ${e.message}")
        }
    }
}
