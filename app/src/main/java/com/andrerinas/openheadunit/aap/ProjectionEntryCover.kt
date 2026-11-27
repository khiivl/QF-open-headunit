package com.andrerinas.openheadunit.aap

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The picture of the screen that is being left for the projection.
 *
 * The projection's first video frame takes a moment, and without it that moment is black. The screen being left hands its
 * picture over here, the projection lays it on top of itself, and fades it away once the video is up.
 */
object ProjectionEntryCover {

    /** A picture older than this is of a screen the user has not just left. */
    private const val FRESH_MS = 3_000L

    /** The copy takes a few milliseconds; past this the screen is left without one. */
    private const val COPY_TIMEOUT_MS = 150L

    private val thread by lazy { HandlerThread("entry-cover").apply { start() } }

    private var picture: Bitmap? = null
    private var takenAt = 0L

    /** Copies what [window] shows. Called on the main thread, while the window still has its surface. */
    fun capture(window: Window) {
        picture = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return
        try {
            val copy = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(window, copy, { result = it; done.countDown() }, Handler(thread.looper))
            if (done.await(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS) && result == PixelCopy.SUCCESS) {
                picture = copy
                takenAt = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            AppLog.w("ProjectionEntryCover: no picture of the screen being left: ${e.message}")
        }
    }

    /** The picture, once, if it is of the screen just left. */
    fun take(): Bitmap? {
        val taken = picture?.takeIf { SystemClock.elapsedRealtime() - takenAt < FRESH_MS }
        picture = null
        return taken
    }
}
