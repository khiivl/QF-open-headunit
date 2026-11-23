package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * Shows the iPhone's CarPlay picture received from the zLink daemon (through [ZlinkHub]) and sends
 * touches back. The zLink app must keep running: it holds the licence and the daemon handshake.
 */
class ZlinkCarPlayActivity : BaseActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: TextView
    private lateinit var connectingView: View
    private lateinit var returnCover: View
    private lateinit var container: FrameLayout
    private var subscription: Closeable? = null
    private var backDialog: AlertDialog? = null
    @Volatile private var videoW = 1024
    @Volatile private var videoH = 608
    @Volatile private var videoActive = false

    private val backKeyPolicy = com.andrerinas.openheadunit.input.BackKeyPolicy()
    private val backKeyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backKeyLongPressRunnable = Runnable {
        if (backKeyPolicy.onTimerExpired() == com.andrerinas.openheadunit.input.BackKeyPolicy.Action.SHOW_EXIT_DIALOG) {
            showBackDialog()
        }
    }
    private val backKeyUpRunnable = Runnable {
        AppLog.i("zlink carplay: back key pulse release (UP)")
        ZlinkHub.injectKey(KeyEvent.KEYCODE_BACK, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance.set(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#12161C"))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        surfaceView = SurfaceView(this)
        container.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        overlayView = TextView(this).apply {
            text = getString(R.string.zlink_connection_lost)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B0000000"))
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
        }
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        container.addView(overlayView, overlayParams)

        // Full screen, so the picture surface (black until the first frame) is never what the user sees while waiting.
        connectingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#12161C"))
            addView(ProgressBar(this@ZlinkCarPlayActivity).apply { isIndeterminate = true })
            addView(TextView(this@ZlinkCarPlayActivity).apply {
                text = getString(R.string.zlink_connecting)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 0)
            })
            visibility = View.VISIBLE
        }
        container.addView(
            connectingView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        // Covers the picture while it is rebuilt after coming back from another screen, then fades away.
        returnCover = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#12161C"))
            addView(
                ProgressBar(this@ZlinkCarPlayActivity).apply { isIndeterminate = true },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
            visibility = View.GONE
        }
        container.addView(
            returnCover,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        setContentView(container)
        container.requestFocus()

        // The CarPlay picture owns the whole screen: hide the status and navigation bars.
        SystemUI.apply(window, container, Settings.FullscreenMode.IMMERSIVE, notesCanvas = false)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = attach(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
        })
        surfaceView.setOnTouchListener { v, e -> forwardTouch(v.width, v.height, e); true }
    }

    /**
     * The first video frame arriving is not the picture being on screen: the decoder still has to produce it.
     * Keep the connecting screen until a frame has actually been shown (or a few seconds, whichever is first).
     */
    private fun hideConnectingWhenShown() {
        val started = android.os.SystemClock.elapsedRealtime()
        var firstShownAt = 0L
        val check = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.elapsedRealtime()
                val shown = ZlinkHub.videoSink?.renderedCount ?: 0L
                if (shown > 0L && firstShownAt == 0L) firstShownAt = now
                // The first frames of a new session are blank: stay until the first one with real content has been
                // shown. A screen that never produces one (or a slow start) is let through after a moment.
                val contentAt = ZlinkHub.videoSink?.contentFrameIndex ?: 0L
                val settled = (contentAt != 0L && shown >= contentAt) ||
                    (firstShownAt != 0L && now - firstShownAt >= SETTLED_MS)
                if (settled || now - started > 4_000L) {
                    connectingView.animate().alpha(0f).setDuration(300).withEndAction {
                        connectingView.visibility = View.GONE
                        connectingView.alpha = 1f
                    }.start()
                } else {
                    connectingView.postDelayed(this, 30)
                }
            }
        }
        connectingView.post(check)
    }

    // A fresh feed subscription replays the current picture group, so each surface starts cleanly.
    private fun attach(holder: SurfaceHolder) {
        val shownBefore = ZlinkHub.videoSink?.renderedCount ?: 0L
        if (ZlinkHub.attachSurface(holder.surface)) showReturnCover(shownBefore)

        // Coming back to a picture that is already running: no connecting screen, the return cover handles it.
        if ((ZlinkHub.videoSink?.decodedCount ?: 0L) > 0L) {
            videoActive = true
            connectingView.visibility = View.GONE
        }

        subscription = ZlinkHub.subscribe(object : ZlinkHub.Listener {
            override fun onFrame(frame: ZlinkWire.Frame) {
                when (frame.channel) {
                    ZlinkWire.CH_VIDEO -> if (frame.type == ZlinkWire.TYPE_VIDEO) {
                        // Only a picture frame counts. The settings packets (SPS and PPS) of an earlier session are
                        // replayed to every new screen at once, long before the phone has sent anything to show.
                        val nal = ZlinkWire.parseVideo(frame.payload)?.nalType ?: -1
                        if (!videoActive && nal != 7 && nal != 8) {
                            videoActive = true
                            runOnUiThread { hideConnectingWhenShown() }
                        }
                    }
                    ZlinkWire.CH_REPLAY_END -> {
                        ZlinkHub.videoSink?.markLive()
                    }
                }
            }

            override fun onReplayEnd() {
                ZlinkHub.videoSink?.markLive()
            }

            override fun onSessionState(state: ZlinkSessionState) {
                AppLog.i("zlink activity: session state is $state")
                runOnUiThread {
                    when (state) {
                        ZlinkSessionState.CONNECTED -> {
                            overlayView.visibility = View.GONE
                            if (!videoActive && (ZlinkHub.videoSink?.decodedCount ?: 0L) == 0L) connectingView.visibility = View.VISIBLE
                        }
                        ZlinkSessionState.LOST -> {
                            connectingView.visibility = View.GONE
                            overlayView.visibility = View.VISIBLE
                        }
                        ZlinkSessionState.GAVE_UP -> {
                            connectingView.visibility = View.GONE
                            overlayView.visibility = View.GONE
                            finish()
                        }
                    }
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            container.requestFocus()
            SystemUI.apply(window, container, Settings.FullscreenMode.IMMERSIVE, notesCanvas = false)
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        container.requestFocus()
        SystemUI.apply(window, container, Settings.FullscreenMode.IMMERSIVE, notesCanvas = false)
        ZlinkHub.resumeOffer()
        ZlinkHub.requestKeyframe()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        container.requestFocus()
        SystemUI.apply(window, container, Settings.FullscreenMode.IMMERSIVE, notesCanvas = false)
        ZlinkHub.resumeOffer()
        ZlinkHub.requestKeyframe()
    }

    override fun onPause() {
        backKeyHandler.removeCallbacks(backKeyLongPressRunnable)
        backKeyHandler.removeCallbacks(backKeyUpRunnable)
        backKeyPolicy.reset()
        isResumed = false
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showBackDialog()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val isLong = event.isLongPress || (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
                val act = backKeyPolicy.onKeyDown(event.eventTime, event.repeatCount > 0, isLong)
                AppLog.i("zlink carplay: dispatchKeyEvent BACK DOWN act=$act repeat=${event.repeatCount} isLong=$isLong")
                if (act == com.andrerinas.openheadunit.input.BackKeyPolicy.Action.SHOW_EXIT_DIALOG) {
                    backKeyHandler.removeCallbacks(backKeyLongPressRunnable)
                    showBackDialog()
                } else if (event.repeatCount == 0) {
                    backKeyHandler.removeCallbacks(backKeyLongPressRunnable)
                    backKeyHandler.postDelayed(backKeyLongPressRunnable, backKeyPolicy.longPressTimeoutMs)
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                backKeyHandler.removeCallbacks(backKeyLongPressRunnable)
                val act = backKeyPolicy.onKeyUp(event.eventTime)
                AppLog.i("zlink carplay: dispatchKeyEvent BACK UP act=$act")
                if (act == com.andrerinas.openheadunit.input.BackKeyPolicy.Action.SHOW_EXIT_DIALOG) {
                    showBackDialog()
                } else if (act == com.andrerinas.openheadunit.input.BackKeyPolicy.Action.FORWARD_BACK) {
                    AppLog.i("zlink carplay: forwarding back key pulse to CarPlay (DOWN now, UP in ${BACK_KEY_PULSE_MS}ms)")
                    backKeyHandler.removeCallbacks(backKeyUpRunnable)
                    ZlinkHub.injectKey(KeyEvent.KEYCODE_BACK, true)
                    backKeyHandler.postDelayed(backKeyUpRunnable, BACK_KEY_PULSE_MS)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showBackDialog() {
        if (backDialog?.isShowing == true) return

        val items = arrayOf(
            getString(R.string.zlink_disconnect_carplay),
            getString(R.string.zlink_go_home)
        )
        val dialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        ZlinkHub.endSession(ZlinkHub.EndReason.USER)
                        // Land on the home screen, not on whatever app happened to be behind this screen.
                        moveToBackground()
                        finish()
                    }
                    1 -> {
                        moveToBackground()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnDismissListener {
            backDialog = null
            SystemUI.apply(window, container, Settings.FullscreenMode.IMMERSIVE, notesCanvas = false)
        }

        val dialogWindow = dialog.window
        dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialogWindow?.let { SystemUI.applySystemBars(it, Settings.FullscreenMode.IMMERSIVE) }
        dialog.show()
        backDialog = dialog
        dialogWindow?.let {
            SystemUI.applySystemBars(it, Settings.FullscreenMode.IMMERSIVE)
            it.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    private fun moveToBackground() {
        val isHome = com.andrerinas.openheadunit.utils.CarLauncherManager.isDefaultLauncher(this)
        val startMain = if (isHome) {
            Intent(this, com.andrerinas.openheadunit.main.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_HOME)
            }
        } else {
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        }
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        AppLog.i("zlink carplay: moving to background, isHome=$isHome")
        startActivity(startMain)
    }

    /** Dark until the rebuilt picture's first frame is on screen (or 1.5 s), then a short fade into it. */
    private fun showReturnCover(shownBefore: Long) {
        returnCover.animate().cancel()
        returnCover.alpha = 1f
        returnCover.visibility = View.VISIBLE
        val started = android.os.SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val shown = (ZlinkHub.videoSink?.renderedCount ?: 0L) > shownBefore
                if (shown || android.os.SystemClock.elapsedRealtime() - started > 8_000L) {
                    returnCover.animate().alpha(0f).setDuration(220).withEndAction {
                        returnCover.visibility = View.GONE
                        returnCover.alpha = 1f
                    }.start()
                } else {
                    returnCover.postDelayed(this, 30)
                }
            }
        }
        returnCover.post(check)
    }

    private fun detach() {
        backDialog?.dismiss()
        backDialog = null
        subscription?.close()
        subscription = null
        ZlinkHub.detachSurface()
        videoActive = false
    }

    override fun onDestroy() {
        activeInstance.compareAndSet(this, null)
        detach()
        super.onDestroy()
    }

    /** The picture fills the view, so a touch maps linearly onto the 1024x608 video plane. */
    private fun forwardTouch(viewW: Int, viewH: Int, e: MotionEvent) {
        if (viewW <= 0 || viewH <= 0) return
        val x = (e.x * videoW / viewW).toInt().coerceIn(0, videoW - 1)
        val y = (e.y * videoH / viewH).toInt().coerceIn(0, videoH - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> ZlinkHub.injectTouch(x, y, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ZlinkHub.injectTouch(x, y, false)
        }
    }

    companion object {
        const val BACK_KEY_PULSE_MS = 60L
        private const val SETTLED_MS = 1500L   // the longest to wait after the first frame for one with real content

        /** True while the CarPlay screen is in front; read by [ZlinkService] before it raises the screen. */
        @Volatile var isResumed = false
            private set
        private val activeInstance = AtomicReference<ZlinkCarPlayActivity?>()

        fun finishActive() {
            activeInstance.get()?.let { act ->
                act.runOnUiThread {
                    act.finish()
                }
            }
        }
    }
}
