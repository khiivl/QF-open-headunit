package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full screen shown when this unit offers the app neither root nor local ADB.
 *
 * Most of what the app does on these units goes through a shell (the hotspot's name and band, the zLink daemon, the key
 * log), so without one it half-works in ways that are hard to tell from a fault. A banner can be dismissed and then
 * forgotten; this one cannot: Back does nothing, there is no close button, and it is shown again every time the main
 * screen is resumed while the condition holds. It closes by itself the moment a shell can be reached, which is also
 * what Retry checks.
 */
class NoShellAccessActivity : AppCompatActivity() {

    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_no_shell_access)
        findViewById<MaterialButton>(R.id.no_shell_retry).setOnClickListener { recheck(userAsked = true) }
        // Raises the su manager's prompt again, which only comes back when no answer is stored there.
        findViewById<MaterialButton>(R.id.no_shell_ask_root).setOnClickListener {
            lifecycleScope.launch {
                OemAppManager.requestRootNow()
                recheck(userAsked = true)
            }
        }
        // The debug switch takes ADB away, so with it on and no root this screen would be a dead end.
        findViewById<MaterialButton>(R.id.no_shell_root_only_off).apply {
            visibility = if (OemAppManager.rootOnly) View.VISIBLE else View.GONE
            setOnClickListener {
                Settings(this@NoShellAccessActivity).rootOnlyDebug = false
                visibility = View.GONE
                recheck(userAsked = true)
            }
        }
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Root granted in the su manager, or ADB switched on, is noticed without a tap.
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(POLL_MS)
                recheck(userAsked = false)
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        super.onPause()
    }

    // Deliberately nothing: the screen is the message, and it goes away when the problem does.
    @Deprecated("Back is disabled on purpose")
    override fun onBackPressed() {}

    private fun recheck(userAsked: Boolean) {
        lifecycleScope.launch {
            if (OemAppManager.hasShellAccess()) {
                finish()
            } else if (userAsked) {
                findViewById<TextView>(R.id.no_shell_status).setText(R.string.no_shell_access_still)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
